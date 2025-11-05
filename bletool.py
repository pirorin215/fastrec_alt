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
received_chunk_count = 0
g_client = None  # Global client object

# Notification handler function
def notification_handler(characteristic: BleakGATTCharacteristic, data: bytearray):
    global received_response_data, is_receiving_file, received_chunk_count, g_client
    if is_receiving_file:
        if data == b'EOF':  # End of file transfer
            print("End of file transfer signal received.")
            response_event.set()
        else:
            received_chunk_count += 1
            print(f"Received chunk {received_chunk_count}, size: {len(data)}")
            received_response_data.extend(data)
            if g_client:
                asyncio.create_task(g_client.write_gatt_char(ACK_UUID, b'ACK'))
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

async def run_ble_command(client: BleakClient, command_str: str, verbose: bool = False, timeout: float = 15.0):
    global received_response_data, is_receiving_file
    is_receiving_file = False
    received_response_data.clear()
    response_event.clear()

    if verbose:
        print(f"\n--- BLEコマンド実行: コマンド='{command_str}' ---")
    if not client.is_connected:
        if not await reconnect_ble_client(client, verbose):
            return None

    if verbose:
        print(f"3. コマンド '{command_str}' を '{COMMAND_UUID}' に送信中...")
    await client.write_gatt_char(COMMAND_UUID, bytes(command_str, 'utf-8'), response=True)
    if verbose:
        print(f"{GREEN}   -> コマンド送信完了。応答を待機中...{RESET}")
    try:
        await asyncio.wait_for(response_event.wait(), timeout=timeout)
        return received_response_data.decode('utf-8')
    except asyncio.TimeoutError:
        print(f"{RED}タイムアウト: 応答データが受信されませんでした。{RESET}")
        return None

async def run_ble_command_for_file(client: BleakClient, command_str: str, verbose: bool = False, timeout: float = 120.0): # Increased timeout
    global received_response_data, is_receiving_file
    is_receiving_file = True
    received_response_data.clear()
    response_event.clear()

    if verbose:
        print(f"\n--- BLEファイル転送コマンド実行: コマンド='{command_str}' ---")
    if not client.is_connected:
        if not await reconnect_ble_client(client, verbose):
            return None

    await client.write_gatt_char(COMMAND_UUID, bytes(command_str, 'utf-8'), response=True)
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

async def reconnect_ble_client(client: BleakClient, verbose: bool = False) -> bool:
    global g_client
    print(f"{RED}BLEクライアントが切断されました。再接続を試みます...{RESET}")
    try:
        if client.is_connected:
            await client.disconnect()
        g_client = BleakClient(client.address)
        await g_client.connect()
        await g_client.start_notify(RESPONSE_UUID, notification_handler)
        print(f"{GREEN}再接続に成功しました。{RESET}")
        return True
    except Exception as e:
        print(f"{RED}再接続に失敗しました: {e}{RESET}")
        return False

async def send_setting_ini(client: BleakClient, file_path: str, verbose: bool = False):
    try:
        with open(file_path, 'r') as f:
            content = f.read()
        command = f"SET:setting_ini:{content}"
        print(f"送信するsetting.iniの内容:\n{content}")
        print(f"{file_path} から setting.ini を送信中...")
        await run_ble_command(client, command, verbose)
        print(f"{GREEN}setting.ini を正常に送信しました。{RESET}")
    except FileNotFoundError:
        print(f"{RED}Error: File not found at {file_path}{RESET}")
    except Exception as e:
        if "disconnected" in str(e):
            print("デバイスがリセットされました。再接続を試みます...")
            await reconnect_ble_client(client, verbose)
            print("「2. デバイスから setting.ini を取得」で反映されたか確認できます")
        else:
            print(f"{RED}Error sending setting.ini: {e}{RESET}")

async def get_setting_ini(client: BleakClient, verbose: bool = False):
    print("デバイスから setting.ini を要求中...")
    device_response = await run_ble_command(client, "GET:setting_ini", verbose)
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

async def get_device_info(client: BleakClient, verbose: bool = False, silent: bool = False):
    if not silent:
        print("デバイスから各種情報を要求中...")
    response = await run_ble_command(client, "GET:info", verbose)
    if response:
        if verbose:
            print(f"{GREEN}マイコンからの情報:{RESET}\n{response}")
        try:
            info = json.loads(response)
            if not silent:
                print("\n")
                print(f"{ 'バッテリーレベル'} : {int(info.get('battery_level', 0))} %")
                print(f"{ 'バッテリー電圧'}   : {info.get('battery_voltage', 0.0):.1f} V")
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

async def get_log_file(client: BleakClient, verbose: bool = False):
    global received_chunk_count
    received_chunk_count = 0 # Reset counter before each download
    info = await get_device_info(client, verbose, silent=True)
    if not info or 'ls' not in info:
        print(f"{RED}ファイルリストの取得に失敗しました。{RESET}")
        return

    ls_content = info.get('ls', '')
    log_files = [f for f in ls_content.strip().split('\n') if f.startswith('log.') and f.endswith('.txt')]

    if not log_files:
        print(f"{RED}ログファイルが見つかりませんでした。{RESET}")
        return

    print("\n取得するログファイルを選択してください:")
    for i, filename in enumerate(log_files):
        print(f"{i + 1}. {filename}")
    print("0. キャンセル")

    sys.stdout.write("Enter your choice: ")
    sys.stdout.flush()
    choice = getch()
    print(choice)

    try:
        choice_num = int(choice)
        if choice_num == 0:
            print("キャンセルしました。")
            return
        if 1 <= choice_num <= len(log_files):
            filename = log_files[choice_num - 1]
        else:
            print(f"{RED}無効な選択です。{RESET}")
            return
    except ValueError:
        print(f"{RED}無効な入力です。{RESET}")
        return

    print(f"デバイスから {filename} を要求中...")
    command = f"GET:log:{filename}"
    file_content = await run_ble_command_for_file(client, command, verbose)

    if file_content is not None:
        print(f"Total received file size: {len(file_content)} bytes")
        try:
            with open(filename, 'wb') as f: # Write as binary
                f.write(file_content)
            print(f"{GREEN}{filename} を正常に取得し、カレントディレクトリに保存しました。{RESET}")
        except IOError as e:
            print(f"{RED}ファイル '{filename}' の保存中にエラーが発生しました: {e}{RESET}")
    else:
        print(f"{RED}{filename} の取得に失敗しました。{RESET}")


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
            g_client = client = BleakClient(device.address)
            print(f"{device.address} に接続中...通知を有効化中...")
            await client.connect()
            await client.start_notify(RESPONSE_UUID, notification_handler)
            print(f"{GREEN}'{RESPONSE_UUID}' の通知を有効化しました。{RESET}")

            while True:
                print("\n--- BLE Tool Menu ---")
                print("1. 録音レコーダに setting.ini を送信")
                print("2. 録音レコーダの setting.ini を表示")
                print("3. 録音レコーダの情報取得")
                print("4. 録音レコーダのログファイルを取得")
                print("0. 終了")
                sys.stdout.write("Enter your choice: ")
                sys.stdout.flush()
                choice = getch()
                print(choice)

                if choice == '1':
                    await send_setting_ini(client, "setting.ini", verbose)
                elif choice == '2':
                    await get_setting_ini(client, verbose)
                elif choice == '3':
                    await get_device_info(client, verbose)
                elif choice == '4':
                    await get_log_file(client, verbose)
                elif choice == '0':
                    print("BLEツールを終了します。")
                    break
                else:
                    print(f"{RED}無効な選択です。もう一度お試しください。{RESET}")

        except Exception as e:
            print(f"{RED}致命的なエラーが発生しました: {e}{RESET}")
        finally:
            if client and client.is_connected:
                await client.stop_notify(RESPONSE_UUID)
                await client.disconnect()

    asyncio.run(main_loop())