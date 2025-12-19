import re
import json
import asyncio
import time
import argparse
import sys
import tty
import termios
from datetime import datetime
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
start_transfer_event = asyncio.Event() # For handshake
is_receiving_file = False
total_received_bytes = 0 
g_client = None  # Global client object
DEVICE_ADDRESS = None # Global device address
g_total_file_size_for_transfer = 0 
file_transfer_start_time = 0.0

# For ACK chunking
g_ack_chunk_size = 1 # Default to 1 (ACK every chunk)
received_chunk_count_for_ack = 0

# Notification handler function
async def notification_handler(characteristic: BleakGATTCharacteristic, data: bytearray):
    global received_response_data, is_receiving_file, g_client, total_received_bytes
    global g_total_file_size_for_transfer, file_transfer_start_time, received_chunk_count_for_ack, g_ack_chunk_size
    if is_receiving_file:
        if data == b'START':
            print("Received START signal.")
            start_transfer_event.set()
            received_chunk_count_for_ack = 0 # Reset chunk counter for new transfer
        elif data == b'EOF' or data.startswith(b'ERROR:'):  # End of file transfer or error
            if data.startswith(b'ERROR:'):
                print(f"\n{RED}マイコンからエラーを受信: {data.decode()}{RESET}")
            else:
                print("\nEnd of file transfer signal received.")
            response_event.set()
            g_total_file_size_for_transfer = 0 # Reset after transfer
            file_transfer_start_time = 0.0 # Reset start time
            received_chunk_count_for_ack = 0 # Reset after transfer
        else:
            total_received_bytes += len(data) # Update total received bytes
            received_response_data.extend(data)
            received_chunk_count_for_ack += 1
            
            elapsed_time = time.time() - file_transfer_start_time
            kbps = 0.0
            if elapsed_time > 0:
                kbps = (total_received_bytes / 1024) / elapsed_time # Kilobytes per second
            
            percentage = 0.0
            if g_total_file_size_for_transfer > 0:
                percentage = (total_received_bytes / g_total_file_size_for_transfer) * 100
                print(f"\r受信: {total_received_bytes} byte, {kbps:.2f} kbps, {elapsed_time:.0f} sec, {percentage:.1f}% ", end="", flush=True)
            else:
                print(f"\r受信: {total_received_bytes} byte, {kbps:.2f} kbps, {elapsed_time:.0f} sec", end="", flush=True)

            if g_client and (received_chunk_count_for_ack % g_ack_chunk_size == 0):
                await g_client.write_gatt_char(ACK_UUID, b'ACK', response=True)
                received_chunk_count_for_ack = 0 # Reset after sending ACK to count for the next batch
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
    start_transfer_event.clear()
    total_received_bytes = 0 
    
    if verbose:
        print(f"\n--- BLEファイル転送コマンド実行: コマンド='{command_str}' ---")
    if not g_client or not g_client.is_connected:
        if not await reconnect_ble_client(verbose):
            return None
    
    await g_client.write_gatt_char(COMMAND_UUID, bytes(command_str, 'utf-8'), response=True)
    if verbose:
        print(f"{GREEN}   -> ファイル転送コマンド送信完了。START信号を待機中...{RESET}")
    
    try:
        # Wait for the START signal from the device
        await asyncio.wait_for(start_transfer_event.wait(), timeout=5.0)
        
        # Send START_ACK to the device
        if verbose:
            print(f"{GREEN}   -> START信号受信。START_ACKを送信...{RESET}")
        await g_client.write_gatt_char(ACK_UUID, b'START_ACK', response=True)
        
        # Now, start the timer and wait for the file data
        file_transfer_start_time = time.time()
        if verbose:
            print(f"{GREEN}   -> ハンドシェイク完了。ファイルデータ受信中...{RESET}")

        await asyncio.wait_for(response_event.wait(), timeout=timeout)
        return received_response_data
    
    except asyncio.TimeoutError:
        if not start_transfer_event.is_set():
            print(f"{RED}タイムアウト: デバイスからSTART信号が受信されませんでした。{RESET}")
        else:
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

                print(f"{ 'LittleFS使用率'}   : {info.get('littlefs_usage_percent', 'N/A')} %")
                print(f"{ 'WAVファイル数'}    : {info.get('wav_count', 'N/A')}")
                print(f"{ 'TXTファイル数'}    : {info.get('txt_count', 'N/A')}")
                print(f"{ 'INIファイル数'}    : {info.get('ini_count', 'N/A')}")

            return info
        except json.JSONDecodeError:
            if not silent:
                print(f"{RED}エラー: 受信した情報がJSON形式ではありません。{RESET}")
            return None
    else:
        if not silent:
            print(f"{RED}各種情報の取得に失敗しました。{RESET}")
        return None

async def synchronize_time(verbose: bool = False):
    print("デバイスの時刻を同期中...")
    current_time = datetime.now()
    # Calculate Unix timestamp (seconds since epoch)
    # Convert datetime object to Unix timestamp
    unix_timestamp = int(current_time.timestamp())
    
    # Change command prefix from CMD:set_time: to SET:time:
    command = f"SET:time:{unix_timestamp}"
    
    response = await run_ble_command(command, verbose)
    if response:
        print(f"{GREEN}時刻同期コマンドがデバイスに送信されました。デバイスからの応答: {response}{RESET}")
    else:
        print(f"{RED}時刻同期に失敗しました。{RESET}")


async def list_files(extension: str, verbose: bool = False):
    """Executes GET:ls command and prints the result."""
    if not extension:
        print(f"{RED}エラー: 拡張子が指定されていません。{RESET}")
        return
    
    # Allow users to enter with or without a dot
    ext_for_command = extension.replace(".", "")
    command = f"GET:ls:{ext_for_command}"
    
    if verbose:
        print(f"ファイルリスト取得コマンド: {command}")

    file_list_json_str = await run_ble_command(command, verbose)

    if not file_list_json_str or file_list_json_str.startswith("ERROR:"):
        print(f"{RED}ファイルの取得に失敗しました: {file_list_json_str}{RESET}")
        return
    
    try:
        files_data = json.loads(file_list_json_str)
        if not files_data:
            print(f"拡張子 '{extension}' を持つファイルは見つかりませんでした。")
            return
        
        print(f"--- ファイルリスト (.{ext_for_command}) ---")
        for file_entry in files_data:
            name = file_entry.get("name", "N/A")
            size = file_entry.get("size", 0)
            print(f"  - {name:<30} {size:>10} bytes")
        print("--------------------")

    except json.JSONDecodeError:
        print(f"{RED}エラー: 受信した情報がJSON形式ではありません。{RESET}")
    

async def get_file_from_device(file_extension_filter: str, verbose: bool = False, ack_chunk_size: int = 1):
    global received_chunk_count_for_ack, g_total_file_size_for_transfer, g_ack_chunk_size
    g_ack_chunk_size = ack_chunk_size # Set the global ACK chunk size
    received_chunk_count_for_ack = 0 

    # Allow users to enter with or without a dot
    ext_for_command = file_extension_filter.replace(".", "")
    command = f"GET:ls:{ext_for_command}"
    if verbose:
        print(f"ファイルリスト取得コマンド: {command}")

    file_list_json_str = await run_ble_command(command, verbose)

    if not file_list_json_str or file_list_json_str.startswith("ERROR:"):
        print(f"{RED}該当するファイルが見つかりませんでした。({file_list_json_str}){RESET}")
        return

    try:
        files_data = json.loads(file_list_json_str)
    except json.JSONDecodeError:
        print(f"{RED}エラー: 受信したファイルリストがJSON形式ではありません。{RESET}")
        return

    if not files_data:
        print(f"{RED}該当するファイルが見つかりませんでした。{RESET}")
        return

    print(f"\n取得するファイルを選択してください:")
    for i, file_entry in enumerate(files_data):
        name = file_entry.get("name", "N/A")
        size = file_entry.get("size", 0)
        print(f"{i + 1}. {name} ({size} bytes)")
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
        if 1 <= choice_num <= len(files_data):
            selected_filename = files_data[choice_num - 1].get("name")
            selected_file_size = files_data[choice_num - 1].get("size", 0)
        else:
            print(f"{RED}無効な選択です。もう一度お試しください。{RESET}")
            return
    except ValueError:
        print(f"{RED}無効な入力です。もう一度お試しください。{RESET}")
        return
    
    g_total_file_size_for_transfer = selected_file_size 

    print(f"デバイスから {selected_filename} を要求中... (予想サイズ: {selected_file_size} bytes)")
    command = f"GET:file:{selected_filename}:{g_ack_chunk_size}"
    file_content = await run_ble_command_for_file(command, verbose)

    if file_content is not None:
        print(f"Total received file size: {len(file_content)} bytes")
        try:
            with open(selected_filename, 'wb') as f:
                f.write(file_content)
            print(f"{GREEN}{selected_filename} を正常に取得し、カレントディレクトリに保存しました。{RESET}")
        except IOError as e:
            print(f"{RED}ファイル '{selected_filename}' の保存中にエラーが発生しました: {e}{RESET}")
    else:
        print(f"{RED}{selected_filename} の取得に失敗しました。{RESET}")

async def delete_wav_files(verbose: bool = False):
    print("WAVファイルを削除します...")
    
    # List WAV files first
    ext_for_command = "wav"
    command = f"GET:ls:{ext_for_command}"
    file_list_json_str = await run_ble_command(command, verbose)

    if not file_list_json_str or file_list_json_str.startswith("ERROR:"):
        print(f"{RED}該当するWAVファイルが見つかりませんでした。({file_list_json_str}){RESET}")
        return

    try:
        files_data = json.loads(file_list_json_str)
    except json.JSONDecodeError:
        print(f"{RED}エラー: 受信したファイルリストがJSON形式ではありません。{RESET}")
        return

    if not files_data:
        print(f"{RED}該当するWAVファイルが見つかりませんでした。{RESET}")
        return

    print(f"\n削除するファイルを選択してください:")
    for i, file_entry in enumerate(files_data):
        name = file_entry.get("name", "N/A")
        size = file_entry.get("size", 0)
        print(f"{i + 1}. {name} ({size} bytes)")
    print("A. 全てのWAVファイルを削除")
    print("0. キャンセル")

    sys.stdout.write("Enter your choice: ")
    sys.stdout.flush()
    choice = getch()
    print(choice)

    selected_filenames = []

    if choice.lower() == 'a':
        print(f"{RED}本当に全てのWAVファイルを削除しますか？ (y/N){RESET}")
        sys.stdout.write("Enter your choice: ")
        sys.stdout.flush()
        confirm = getch()
        print(confirm)
        if confirm.lower() == 'y':
            for file_entry in files_data:
                selected_filenames.append(file_entry.get("name"))
        else:
            print("\nキャンセルしました。")
            return
    elif choice == '0':
        print("\nキャンセルしました。")
        return
    else:
        try:
            choice_num = int(choice)
            if 1 <= choice_num <= len(files_data):
                selected_filenames.append(files_data[choice_num - 1].get("name"))
            else:
                print(f"{RED}無効な選択です。もう一度お試しください。{RESET}")
                return
        except ValueError:
            print(f"{RED}無効な入力です。もう一度お試しください。{RESET}")
            return
            
    if not selected_filenames:
        print("削除対象のファイルがありません。")
        return

    for filename in selected_filenames:
        print(f"デバイスから {filename} を削除中...")
        delete_command = f"DEL:file:{filename}"
        response = await run_ble_command(delete_command, verbose)
        if response and "OK" in response: # Assuming "OK" for success
            print(f"{GREEN}{filename} を正常に削除しました。{RESET}")
        else:
            print(f"{RED}{filename} の削除に失敗しました: {response}{RESET}")

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


async def main():
    parser = argparse.ArgumentParser(description='BLE Tool for fastrec device. Run without arguments for interactive menu.')
    parser.add_argument('-v', '--verbose', action='store_true', help='Enable verbose output.')
    parser.add_argument('-a', '--ack-size', type=int, default=1, help='Set the ACK chunk size for file transfers (default: 1).')
    
    subparsers = parser.add_subparsers(dest='command', help='Sub-command help')

    parser_info = subparsers.add_parser('info', help='Get device information.')
    
    parser_ls = subparsers.add_parser('ls', help='List files with a specific extension.')
    parser_ls.add_argument('extension', type=str, help='File extension to list (e.g., "wav", "log").')

    parser_get = subparsers.add_parser('get', help='Interactively get a file with a specific extension.')
    parser_get.add_argument('extension', type=str, help='File extension to get (e.g., "wav", "log").')

    parser_get_ini = subparsers.add_parser('get_ini', help='Get setting.ini from the device.')
    
    parser_set_ini = subparsers.add_parser('set_ini', help='Send local setting.ini to the device.')
    parser_set_ini.add_argument('file', type=str, nargs='?', default='setting.ini', help='Path to the setting.ini file.')
    
    parser_reset = subparsers.add_parser('reset', help='Factory reset the device.')

    args = parser.parse_args()
    verbose = args.verbose
    
    global g_ack_chunk_size
    g_ack_chunk_size = args.ack_size
    
    global g_client
    try:
        if args.command: # If a subcommand is given, run non-interactively
            print(f"BLEデバイス '{DEVICE_NAME}' をスキャン中...")
            device = await BleakScanner.find_device_by_name(DEVICE_NAME, timeout=10.0)
            if not device:
                print(f"{RED}エラー: '{DEVICE_NAME}' デバイスが見つかりませんでした。{RESET}")
                return
            print(f"{GREEN}デバイス発見: {device.address}{RESET}")
            
            global DEVICE_ADDRESS
            DEVICE_ADDRESS = device.address
            g_client = BleakClient(DEVICE_ADDRESS)

            print(f"{DEVICE_ADDRESS} に接続中...")
            await g_client.connect()
            await g_client.start_notify(RESPONSE_UUID, notification_handler)
            print(f"{GREEN}'{RESPONSE_UUID}' の通知を有効化しました。{RESET}")
            
            if args.command == 'info':
                await get_device_info(verbose)
            elif args.command == 'ls':
                await list_files(args.extension, verbose)
            elif args.command == 'get':
                await get_file_from_device(args.extension, verbose, ack_chunk_size=g_ack_chunk_size)
            elif args.command == 'get_ini':
                await get_setting_ini(verbose)
            elif args.command == 'set_ini':
                await send_setting_ini(args.file, verbose)
            elif args.command == 'reset':
                await reset_all(verbose)

        else: # No subcommand, run interactive menu
            await main_loop(verbose)

    except Exception as e:
        print(f"{RED}致命的なエラーが発生しました: {e}{RESET}")
    finally:
        if g_client and g_client.is_connected:
            await g_client.stop_notify(RESPONSE_UUID)
            await g_client.disconnect()
            print("BLE接続を切断しました。")


async def main_loop(verbose: bool = False):
    global g_client, g_ack_chunk_size
    try:
        print(f"BLEデバイス '{DEVICE_NAME}' をスキャン中...")
        device = await BleakScanner.find_device_by_name(DEVICE_NAME, timeout=10.0)
        if not device:
            print(f"{RED}エラー: '{DEVICE_NAME}' デバイスが見つかりませんでした。{RESET}")
            return
        print(f"{GREEN}デバイス発見: {device.address}{RESET}")
        
        global DEVICE_ADDRESS
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
            print("6. 録音レコーダのWAVファイルを削除")
            print("7. 録音レコーダの時刻合わせ")
            print("8. ACKチャンクサイズを設定")
            print(f"{RED}9. デバイスの初期化{RESET}")
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
                await get_file_from_device("txt", verbose, ack_chunk_size=g_ack_chunk_size)
            elif choice == '5':
                await get_file_from_device("wav", verbose, ack_chunk_size=g_ack_chunk_size)
            elif choice == '6':
                await delete_wav_files(verbose)
            elif choice == '7':
                await synchronize_time(verbose)
            elif choice == '8':
                print(f"ACKチャンクサイズを入力してください (現在の設定: {g_ack_chunk_size}): ", end="")
                sys.stdout.flush()
                ack_input = getch()
                print(ack_input)
                try:
                    new_ack_size = int(ack_input)
                    if new_ack_size > 0:
                        g_ack_chunk_size = new_ack_size
                        print(f"ACKチャンクサイズを {g_ack_chunk_size} に設定しました。")
                    else:
                        print(f"{RED}無効な入力です。正の整数を入力してください。{RESET}")
                except ValueError:
                    print(f"{RED}無効な入力です。数値を入力してください。{RESET}")
            elif choice == '9':
                await reset_all(verbose)
            elif choice == '0':
                print("BLEツールを終了します。")
                break
            else:
                print(f"{RED}無効な選択です。もう一度お試しください。{RESET}")

    except Exception as e:
        # Catch exceptions to ensure we disconnect cleanly
        print(f"{RED}エラーが発生しました: {e}{RESET}")
    finally:
        # This block will run even if an exception occurs in the try block
        if g_client and g_client.is_connected:
            # The notification stop might fail if the device is already gone,
            # so a simple try/except pass can be useful here.
            try:
                await g_client.stop_notify(RESPONSE_UUID)
            except Exception as e:
                pass # Ignore errors on stop_notify
            await g_client.disconnect()
            print("BLE接続を切断しました。")


if __name__ == "__main__":
    asyncio.run(main())
