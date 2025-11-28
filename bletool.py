import re
import json
import asyncio
import time
import argparse
import sys
import tty
import termios
from bleak import BleakClient, BleakScanner
from bleak.backends.characteristic import BleakGATTCharacteristic

GREEN = '\033[92m'
RED = '\033[91m'
RESET = '\033[0m'

DEVICE_NAME = "fastrec"
COMMAND_UUID = "beb5483e-36e1-4688-b7f5-ea07361b26aa"
RESPONSE_UUID = "beb5483e-36e1-4688-b7f5-ea07361b26ab"
ACK_UUID = "beb5483e-36e1-4688-b7f5-ea07361b26ac"

# Global variables and events for response data
received_response_data = bytearray()
response_event = asyncio.Event()
is_receiving_file = False
total_received_bytes = 0
g_client = None  # Global client object
DEVICE_ADDRESS = None # Global device address
g_total_file_size_for_transfer = 0
file_transfer_start_time = 0.0
received_chunk_count = 0  # Counter for sliding window

# Notification handler function
async def notification_handler(characteristic: BleakGATTCharacteristic, data: bytearray):
    global received_response_data, is_receiving_file, g_client, total_received_bytes, received_chunk_count
    global g_total_file_size_for_transfer, file_transfer_start_time

    WINDOW_SIZE = 10

    if is_receiving_file:
        if data == b'EOF':  # End of file transfer
            print("\nEnd of file transfer signal received.")
            # Send final ACK for the last partial window.
            if received_chunk_count % WINDOW_SIZE != 0:
                if g_client:
                    await g_client.write_gatt_char(ACK_UUID, b'ACK', response=True)
            response_event.set()
            g_total_file_size_for_transfer = 0
            file_transfer_start_time = 0.0
        else:
            received_chunk_count += 1
            total_received_bytes += len(data)

            elapsed_time = time.time() - file_transfer_start_time
            kbps = 0.0
            if elapsed_time > 0:
                kbps = (total_received_bytes * 8) / (elapsed_time * 1024)  # Kilobits per second

            percentage = 0.0
            if g_total_file_size_for_transfer > 0:
                percentage = (total_received_bytes / g_total_file_size_for_transfer) * 100
                print(f"\r受信: {total_received_bytes} byte, {kbps:.2f} kbps, {elapsed_time:.0f} sec, {percentage:.1f}% ", end="", flush=True)
            else:
                print(f"\r受信: {total_received_bytes} byte, {kbps:.2f} kbps, {elapsed_time:.0f} sec", end="", flush=True)

            received_response_data.extend(data)
            # Send ACK every WINDOW_SIZE chunks
            if g_client and (received_chunk_count % WINDOW_SIZE == 0):
                await g_client.write_gatt_char(ACK_UUID, b'ACK', response=True)
    else:
        received_response_data = data
        response_event.set()

def compare_and_print_diff(device_content: str, local_content: str):
    device_lines = device_content.splitlines()
    local_lines = local_content.splitlines()
    max_len = max(len(device_lines), len(local_lines))
    print("\n変更差分:")
    has_diff = False
    for i in range(max_len):
        device_line = device_lines[i] if i < len(device_lines) else ""
        local_line = local_lines[i] if i < len(local_lines) else ""
        if device_line != local_line:
            has_diff = True
            if device_line:
                print(f"{RED}- {device_line}{RESET}")
            if local_line:
                print(f"{GREEN}+ {local_line}{RESET}")
    if not has_diff:
        print("なし")

def getch():
    fd = sys.stdin.fileno()
    old_settings = termios.tcgetattr(fd)
    try:
        tty.setraw(sys.stdin.fileno())
        ch = sys.stdin.read(1)
        if ch == '\x03':  # Ctrl+C
            raise KeyboardInterrupt
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)
    return ch

async def run_ble_command(command_str: str, verbose: bool = False, timeout: float = 15.0):
    global received_response_data, is_receiving_file, g_client
    is_receiving_file = False
    received_response_data.clear()
    response_event.clear()

    if verbose:
        print(f"\n--- BLEコマンド実行: コマンド='{command_str}' ---")
    if not g_client or not g_client.is_connected:
        if not await reconnect_ble_client(verbose):
            return None

    if verbose:
        print(f"3. コマンド '{command_str}' を '{COMMAND_UUID}' に送信中...")
    await g_client.write_gatt_char(COMMAND_UUID, bytes(command_str, 'utf-8'), response=True)
    if verbose:
        print(f"{GREEN}   -> コマンド送信完了。応答を待機中...{RESET}")
    try:
        await asyncio.wait_for(response_event.wait(), timeout=timeout)
        return received_response_data.decode('utf-8')
    except asyncio.TimeoutError:
        print(f"{RED}タイムアウト: 応答データが受信されませんでした。{RESET}")
        return None

async def run_ble_command_for_file(command_str: str, verbose: bool = False, timeout: float = 120.0): # Increased timeout
    global received_response_data, is_receiving_file, total_received_bytes, g_client, file_transfer_start_time
    is_receiving_file = True
    received_response_data.clear()
    response_event.clear()
    total_received_bytes = 0 
    file_transfer_start_time = time.time()

    if verbose:
        print(f"\n--- BLEファイル転送コマンド実行: コマンド='{command_str}' ---")
    if not g_client or not g_client.is_connected:
        if not await reconnect_ble_client(verbose):
            return None
    
    await g_client.write_gatt_char(COMMAND_UUID, bytes(command_str, 'utf-8'), response=True)
    if verbose:
        print(f"{GREEN}   -> ファイル転送コマンド送信完了。応答を待機中...{RESET}")
    try:
        await asyncio.wait_for(response_event.wait(), timeout=timeout)
        return received_response_data
    except asyncio.TimeoutError:
        print(f"{RED}タイムアウト: ファイルデータが受信されませんでした。{RESET}")
        return None
    finally:
        is_receiving_file = False

async def reconnect_ble_client(verbose: bool = False) -> bool:
    global g_client, DEVICE_ADDRESS
    print(f"{RED}BLEクライアントが切断されました。再接続を試みます...{RESET}")

    address = DEVICE_ADDRESS
    if not address and g_client:
        address = g_client.address

    try:
        if g_client and g_client.is_connected:
            await g_client.disconnect()

        if not address:
            print(f"{RED}エラー: デバイスアドレスが不明です。{RESET}")
            return False

        if not DEVICE_ADDRESS:
            DEVICE_ADDRESS = address

        g_client = BleakClient(address)
        await g_client.connect()
        await g_client.start_notify(RESPONSE_UUID, notification_handler)
        print(f"{GREEN}再接続に成功しました。{RESET}")
        return True
    except Exception as e:
        print(f"{RED}再接続に失敗しました: {e}{RESET}")
        g_client = None
        return False

async def send_setting_ini(file_path: str, verbose: bool = False):
    global g_client
    try:
        with open(file_path, 'r') as f:
            content = f.read()
        command = f"SET:setting_ini:{content}"
        print(f"送信するsetting.iniの内容:\n{content}")
        print(f"{file_path} から setting.ini を送信中...")

        if not g_client or not g_client.is_connected:
            if not await reconnect_ble_client(verbose):
                print(f"{RED}送信前に再接続できませんでした。{RESET}")
                return

        # The device will restart upon receiving this command, likely causing a disconnection error.
        await g_client.write_gatt_char(COMMAND_UUID, bytes(command, 'utf-8'), response=True)
        print("setting.ini を送信しました。デバイスが再起動します。")

    except Exception as e:
        if "disconnected" in str(e).lower():
            # This is an expected outcome as the device reboots.
            print("setting.ini を送信しました。デバイスが再起動します。")
        else:
            # For any other error, print it and stop.
            print(f"{RED}予期せぬエラーが発生しました: {e}{RESET}")
            return  # Stop if the error was not a disconnection

    # After sending the setting.ini, the device reboots. We need to reconnect.
    await handle_reboot_and_reconnect(verbose)

async def handle_reboot_and_reconnect(verbose: bool = False):
    """Handles the device reboot by disconnecting and attempting to reconnect."""
    global g_client
    try:
        # The client might already be disconnected, but we can try to disconnect cleanly if it's not.
        if g_client and g_client.is_connected:
            await g_client.disconnect()

        print("デバイスの再起動後、自動で再接続します...")

        reconnect_attempts = 10
        for i in range(reconnect_attempts):
            print(f"再接続試行 ({i + 1}/{reconnect_attempts})...")
            if await reconnect_ble_client(verbose=False):
                return  # Success, reconnect_ble_client prints success message
            await asyncio.sleep(1.0)

        print(f"{RED}自動再接続に失敗しました。{RESET}")
        print("デバイスの準備ができてから、他のメニュー項目を選択して手動で再接続してください。")

    except Exception as e:
        print(f"{RED}再接続中にエラーが発生しました: {e}{RESET}")

async def get_setting_ini(verbose: bool = False):
    print("デバイスから setting.ini を要求中...")
    device_response = await run_ble_command("GET:setting_ini", verbose)
    if device_response:
        print(f"\n{GREEN}マイコンのsetting.ini:\n{RESET}{device_response}")
        try:
            with open("setting.ini", 'r') as f:
                local_content = f.read()
            compare_and_print_diff(device_response, local_content)
        except FileNotFoundError:
            print(f"{RED}ローカルの setting.ini が見つかりませんでした。{RESET}")
    else:
        print(f"{RED}setting.ini の取得に失敗しました。{RESET}")

async def get_device_info(verbose: bool = False, silent: bool = False):
    if not silent:
        print("デバイスから各種情報を要求中...")
    response = await run_ble_command("GET:info", verbose)
    if response:
        if verbose:
            print(f"{GREEN}マイコンからの情報:{RESET}\n{response}")
        try:
            info = json.loads(response)
            if not silent:
                print("\n")
                print(f"{ 'バッテリーレベル'} : {int(info.get('battery_level', 0))} %")
                print(f"{ 'バッテリー電圧'}   : {info.get('battery_voltage', 0.0):.2f} V")
                print(f"{ 'アプリ状態'}       : {info.get('app_state', 'N/A')}")
                print(f"{ 'WiFi接続状態'}     : {info.get('wifi_status', 'N/A')}")
                print(f"{ '接続済みSSID'}     : {info.get('connected_ssid', 'N/A')}")
                print(f"{ 'WiFi RSSI'}        : {info.get('wifi_rssi', 'N/A')}")
                print(f"{ 'LittleFS使用率'}   : {info.get('littlefs_usage_percent', 'N/A')} %")
                ls_content = info.get('ls', '')
                if ls_content:
                    print(f"{ 'ディレクトリ一覧'}:")
                    for item in ls_content.strip().split('\n'):
                        if item:
                            # Check if it's a file with size info (e.g., "filename (12345 bytes)")
                            if " (" in item and item.endswith(" bytes)"):
                                parts = item.rsplit(" (", 1)
                                filename = parts[0]
                                size_info = parts[1][:-1] # Remove the closing parenthesis
                                print(f"  - {filename:<30} {size_info:>15}") # Aligned output
                            elif item.endswith("/"): # Directory
                                print(f"  - {item}")
                            else: # File without explicit size (shouldn't happen after .ino change, but for robustness)
                                print(f"  - {item}")
                else:
                    print(f"  { 'ディレクトリ一覧'}: N/A")
            return info
        except json.JSONDecodeError:
            if not silent:
                print(f"{RED}エラー: 受信した情報がJSON形式ではありません。{RESET}")
            return None
    else:
        if not silent:
            print(f"{RED}各種情報の取得に失敗しました。{RESET}")
        return None

async def get_log_file(verbose: bool = False):
    global received_chunk_count
    received_chunk_count = 0 # Reset counter before each download
    info = await get_device_info(verbose, silent=True)
    if not info or 'ls' not in info:
        print(f"{RED}ファイルリストの取得に失敗しました。{RESET}")
        return

    ls_content = info.get('ls', '')
    log_files_with_sizes = []
    for item in ls_content.strip().split('\n'):
        if item.startswith('log.'): # Changed from item.endswith('.txt')
            # Parse "log.0.txt (26762 bytes)" format
            match = re.match(r"(.+?)\s+\((\d+)\s+bytes\)", item)
            if match:
                filename = match.group(1)
                size = int(match.group(2))
                log_files_with_sizes.append((filename, size))
            else:
                # Fallback for unexpected format (shouldn't happen with updated ble_setting.ino)
                # or for items that match log. and .txt but don't have size info for some reason
                # We'll try to extract filename and assume 0 size
                filename_only_match = re.match(r"(.+?)\.txt", item)
                if filename_only_match:
                    log_files_with_sizes.append((filename_only_match.group(0), 0))
                else:
                    log_files_with_sizes.append((item.strip(), 0))

    if not log_files_with_sizes:
        print(f"{RED}ログファイルが見つかりませんでした。{RESET}")
        return

    print("\n取得するログファイルを選択してください:")
    for i, (filename, size) in enumerate(log_files_with_sizes):
        print(f"{i + 1}. {filename} ({size} bytes)")
    print("0. キャンセル")

    sys.stdout.write("Enter your choice: ")
    sys.stdout.flush()
    choice = getch()
    print(choice)

    selected_filename = None
    selected_file_size = 0

    try:
        choice_num = int(choice)
        if choice_num == 0:
            print("キャンセルしました。")
            return
        if 1 <= choice_num <= len(log_files_with_sizes):
            selected_filename, selected_file_size = log_files_with_sizes[choice_num - 1]
        else:
            print(f"{RED}無効な選択です。もう一度お試しください。{RESET}")
            return
    except ValueError:
        print(f"{RED}無効な入力です。もう一度お試しください。{RESET}")
        return

    print(f"デバイスから {selected_filename} を要求中... (予想サイズ: {selected_file_size} bytes)")
    command = f"GET:log:{selected_filename}"
    global g_total_file_size_for_transfer
    g_total_file_size_for_transfer = selected_file_size
    file_content = await run_ble_command_for_file(command, verbose)

    if file_content is not None:
        print(f"Total received file size: {len(file_content)} bytes")
        try:
            with open(selected_filename, 'wb') as f: # Write as binary
                f.write(file_content)
            print(f"{GREEN}{selected_filename} を正常に取得し、カレントディレクトリに保存しました。{RESET}")
        except IOError as e:
            print(f"{RED}ファイル '{filename}' の保存中にエラーが発生しました: {e}{RESET}")
    else:
        print(f"{RED}{filename} の取得に失敗しました。{RESET}")


async def get_wav_file(verbose: bool = False):
    global received_chunk_count
    received_chunk_count = 0 # Reset counter before each download
    info = await get_device_info(verbose, silent=True)
    if not info or 'ls' not in info:
        print(f"{RED}ファイルリストの取得に失敗しました。{RESET}")
        return

    ls_content = info.get('ls', '')
    wav_files_with_sizes = []
    for item in ls_content.strip().split('\n'):
        if ".wav" in item:
            # Parse "audio_rec_YYMMDD_HHMMSS.wav (1234567 bytes)" format
            match = re.match(r"(.+?)\s+\((\d+)\s+bytes\)", item)
            if match:
                filename = match.group(1)
                size = int(match.group(2))
                wav_files_with_sizes.append((filename, size))
            else:
                # Fallback for unexpected format
                filename_only_match = re.match(r"(.+?)\.wav", item)
                if filename_only_match:
                    wav_files_with_sizes.append((filename_only_match.group(0), 0))
                else:
                    wav_files_with_sizes.append((item.strip(), 0))


    if not wav_files_with_sizes:
        print(f"{RED}WAVファイルが見つかりませんでした。{RESET}")
        return

    print("\n取得するWAVファイルを選択してください:")
    for i, (filename, size) in enumerate(wav_files_with_sizes):
        print(f"{i + 1}. {filename} ({size} bytes)")
    print("0. キャンセル")

    sys.stdout.write("Enter your choice: ")
    sys.stdout.flush()
    choice = getch()
    print(choice)

    selected_filename = None
    selected_file_size = 0

    try:
        choice_num = int(choice)
        if choice_num == 0:
            print("キャンセルしました。")
            return
        if 1 <= choice_num <= len(wav_files_with_sizes):
            selected_filename, selected_file_size = wav_files_with_sizes[choice_num - 1]
        else:
            print(f"{RED}無効な選択です。もう一度お試しください。{RESET}")
            return
    except ValueError:
        print(f"{RED}無効な入力です。もう一度お試しください。{RESET}")
        return

    print(f"デバイスから {selected_filename} を要求中... (予想サイズ: {selected_file_size} bytes)")
    command = f"GET:wav:{selected_filename}"
    global g_total_file_size_for_transfer
    g_total_file_size_for_transfer = selected_file_size
    file_content = await run_ble_command_for_file(command, verbose)

    if file_content is not None:
        print(f"Total received file size: {len(file_content)} bytes")
        try:
            with open(selected_filename, 'wb') as f: # Write as binary
                f.write(file_content)
            print(f"{GREEN}{selected_filename} を正常に取得し、カレントディレクトリに保存しました。{RESET}")
        except IOError as e:
            print(f"{RED}ファイル '{selected_filename}' の保存中にエラーが発生しました: {e}{RESET}")
    else:
        print(f"{RED}{selected_filename} の取得に失敗しました。{RESET}")


async def reset_all(verbose: bool = False):
    print(f"\n{RED}デバイスを完全にリセット。続行しますか？ (y/N){RESET}")
    sys.stdout.write("Enter your choice: ")
    sys.stdout.flush()
    choice = getch()
    print(choice)

    if choice.lower() != 'y':
        print("\nキャンセルしました。")
        return

    print("\nデバイスの全ファイルを消去するコマンドを送信中...")
    try:
        response = await run_ble_command("CMD:reset_all", verbose)
        if response:
            print(f"\n{GREEN}デバイスからの応答:{RESET} {response}")
    except Exception as e:
        if "disconnected" in str(e).lower():
            # This is an expected outcome as the device reboots.
            print(f"\n{GREEN}デバイスがリセットされ、再起動します。{RESET}")
        else:
            print(f"\n{RED}コマンドの実行中に予期せぬエラーが発生しました: {e}{RESET}")
            return # Do not attempt to reconnect if it wasn't a disconnect error

    # After sending the reset command, the device reboots. We need to reconnect.
    await handle_reboot_and_reconnect(verbose)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='BLE Tool for fastrec device.')
    parser.add_argument('-v', '--verbose', action='store_true', help='Enable verbose output.')
    args = parser.parse_args()
    verbose = args.verbose

    async def main_loop():
        global g_client
        client = None
        try:
            print(f"BLEデバイス '{DEVICE_NAME}' をスキャン中...")
            device = await BleakScanner.find_device_by_name(DEVICE_NAME, timeout=10.0)
            if not device:
                print(f"{RED}エラー: '{DEVICE_NAME}' デバイスが見つかりませんでした。{RESET}")
                return
            print(f"{GREEN}デバイス発見: {device.address}{RESET}")
            DEVICE_ADDRESS = device.address # Store device address globally
            g_client = BleakClient(DEVICE_ADDRESS)
            print(f"{DEVICE_ADDRESS} に接続中...通知を有効化中...")
            await g_client.connect()
            await g_client.start_notify(RESPONSE_UUID, notification_handler)
            print(f"{GREEN}'{RESPONSE_UUID}' の通知を有効化しました。{RESET}")

            while True:
                print("\n--- BLE Tool Menu ---")
                print("1. 録音レコーダに setting.ini を送信")
                print("2. 録音レコーダの setting.ini を表示")
                print("3. 録音レコーダの情報取得")
                print("4. 録音レコーダのログファイルを取得")
                print("5. 録音レコーダのWAVファイルを取得")
                print(f"{RED}6. デバイスの初期化{RESET}")
                print("0. 終了")
                sys.stdout.write("Enter your choice: ")
                sys.stdout.flush()
                choice = getch()
                print(choice)

                if choice == '1':
                    await send_setting_ini("setting.ini", verbose)
                elif choice == '2':
                    await get_setting_ini(verbose)
                elif choice == '3':
                    await get_device_info(verbose)
                elif choice == '4':
                    await get_log_file(verbose)
                elif choice == '5':
                    await get_wav_file(verbose)
                elif choice == '6':
                    await reset_all(verbose)
                elif choice == '0':
                    print("BLEツールを終了します。")
                    break
                else:
                    print(f"{RED}無効な選択です。もう一度お試しください。{RESET}")

        except Exception as e:
            print(f"{RED}致命的なエラーが発生しました: {e}{RESET}")
        finally:
            if g_client and g_client.is_connected:
                await g_client.stop_notify(RESPONSE_UUID)
                await g_client.disconnect()

    asyncio.run(main_loop())
