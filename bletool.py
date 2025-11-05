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
COMMAND_UUID = "beb5483e-36e1-4688-b7f5-ea07361b26aa" # コマンド送信用のUUID
RESPONSE_UUID = "beb5483e-36e1-4688-b7f5-ea07361b26ab" # 応答データ受信用のUUID

# 応答データと状態管理のためのグローバル変数
ble_mode = 'normal'  # 'normal' or 'file_transfer'
received_response_data = None
response_event = asyncio.Event()

file_data = bytearray()
file_size = 0
file_transfer_event = asyncio.Event()

# 状態に応じた単一の通知ハンドラ
def notification_handler(sender, data):
    global ble_mode, received_response_data, response_event, file_data, file_transfer_event

    if ble_mode == 'file_transfer':
        file_data.extend(data)
        file_transfer_event.set() # Signal that a chunk has been received
    else: # normal mode
        received_response_data = data.decode('utf-8')
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

        if device_line == local_line:
            pass
        else:
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

async def run_ble_command(client: BleakClient, command_str: str, verbose: bool = False):
    global received_response_data, ble_mode
    ble_mode = 'normal'
    received_response_data = None
    response_event.clear()

    if verbose:
        print(f"\n--- BLEコマンド実行: コマンド='{command_str}' ---")

    if not client.is_connected:
        if not await reconnect_ble_client(client, verbose):
            return None

    await client.write_gatt_char(COMMAND_UUID, bytes(command_str, 'utf-8'), response=True)
    if verbose:
        print(f"{GREEN}   -> コマンド送信完了。応答を待機中...{RESET}")
    try:
        await asyncio.wait_for(response_event.wait(), timeout=15.0)
    except asyncio.TimeoutError:
        print(f"{RED}タイムアウト: 応答データが受信されませんでした。{RESET}")
    return received_response_data

async def reconnect_ble_client(client: BleakClient, verbose: bool = False) -> bool:
    print(f"{RED}BLEクライアントが切断されました。再接続を試みます...{RESET}")
    try:
        if client.is_connected:
            await client.disconnect()
        await client.connect()
        try:
            await client.start_notify(RESPONSE_UUID, notification_handler)
        except Exception as e:
            if "notifications already started" not in str(e):
                raise e # Re-raise other errors
            else:
                if verbose:
                    print("Notifications were already started, continuing.")

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
        if "disconnected" in str(e) and "setting.ini" in file_path:
            print(f"デバイスがリセットされました")
            print(f"再接続を試みます...")
            await reconnect_ble_client(client, verbose)
            print(f"「2. デバイスから setting.ini を取得」で反映されたか確認できます")
        else:
            print(f"{RED}Error sending setting.ini: {e}{RESET}")

async def get_setting_ini(client: BleakClient, verbose: bool = False):
    print("デバイスから setting.ini を要求中...")
    device_response = await run_ble_command(client, "GET:setting_ini", verbose)

    if device_response:
        print(f"\n\n")
        print(f"{GREEN}マイコンのsetting.ini:\n{RESET}{device_response}")

        local_setting_ini_path = "setting.ini"
        try:
            with open(local_setting_ini_path, 'r') as f:
                local_content = f.read()
            compare_and_print_diff(device_response, local_content)
        except FileNotFoundError:
            print(f"{RED}ローカルの {local_setting_ini_path} が見つかりませんでした。{RESET}")
        except Exception as e:
            print(f"{RED}ローカルの {local_setting_ini_path} の読み込み中にエラーが発生しました: {e}{RESET}")
    else:
        print(f"{RED}setting.ini の取得に失敗しました。{RESET}")
    return device_response

async def get_device_info(client: BleakClient, verbose: bool = False):
    print("デバイスから各種情報を要求中...")
    response = await run_ble_command(client, "GET:info", verbose)
    if response:
        if verbose:
            print(f"{GREEN}マイコンからの情報:{RESET}\n{response}")
        try:
            print(f"\n")
            info = json.loads(response)
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
        except json.JSONDecodeError:
            print(f"{RED}エラー: 受信した情報がJSON形式ではありません。{RESET}")
    else:
        print(f"{RED}各種情報の取得に失敗しました。{RESET}")
    return response

async def progress_reporter(total_size):
    global file_data
    while True:
        try:
            progress = len(file_data)
            if total_size > 0:
                percentage = (progress / total_size) * 100
                print(f"受信中: {progress} / {total_size} bytes ({percentage:.1f}%)", end='\r')
            else:
                print(f"受信中: {progress} bytes", end='\r')
            await asyncio.sleep(1)
        except asyncio.CancelledError:
            break

async def get_log_file(client: BleakClient, verbose: bool = False):
    global ble_mode, file_data, file_transfer_event

    print("デバイス上のログファイル一覧を取得中...")
    ls_response = await run_ble_command(client, "GET:ls", verbose)
    if not ls_response:
        print(f"{RED}ファイルリストの取得に失敗しました。{RESET}")
        return

    files = [f for f in ls_response.strip().split('\n') if f.startswith('log.')]
    if not files:
        print("取得可能なログファイルがありません。")
        return

    print("取得するログファイルを選択してください:")
    for i, f in enumerate(files):
        print(f"{i + 1}. {f}")
    
    sys.stdout.write("番号を選択: ")
    sys.stdout.flush()
    choice = getch()
    print(choice)

    try:
        choice_index = int(choice) - 1
        filename_to_get = files[choice_index]
        full_path = f"/{filename_to_get}"
    except (ValueError, IndexError):
        print(f"{RED}無効な選択です。{RESET}")
        return

    print(f"'{filename_to_get}' を取得中...")
    
    # 1. Get file size
    size_response = await run_ble_command(client, f"GET:log_size:{full_path}", verbose)
    if not size_response or not size_response.startswith("LOG_SIZE:"):
        print(f"{RED}ファイルサイズの取得に失敗しました: {size_response}{RESET}")
        return
    
    total_size = int(size_response.split(':')[1])
    print(f"ファイルサイズ: {total_size} bytes")

    # 2. Receive file chunk by chunk
    ble_mode = 'file_transfer'
    file_data.clear()
    progress_task = asyncio.create_task(progress_reporter(total_size))

    try:
        offset = 0
        while offset < total_size:
            file_transfer_event.clear()
            await client.write_gatt_char(COMMAND_UUID, f"GET:log:{full_path}:{offset}".encode('utf-8'))
            await asyncio.wait_for(file_transfer_event.wait(), timeout=5.0)
            offset += len(file_data) - offset # Bleak can sometimes merge notifications

        print(f"\n{GREEN}ファイル転送完了。{RESET}")

    except asyncio.TimeoutError:
        print(f"\n{RED}チャンクの受信中にタイムアウトしました。{RESET}")
    except Exception as e:
        print(f"\n{RED}ファイル取得中にエラーが発生しました: {e}{RESET}")
    finally:
        progress_task.cancel()
        ble_mode = 'normal'

    if len(file_data) > 0:
        with open(filename_to_get, 'wb') as f:
            f.write(file_data)
        print(f"{GREEN}ファイル '{filename_to_get}' として {len(file_data)} バイト保存しました。{RESET}")

async def format_filesystem(client: BleakClient, verbose: bool = False):
    print(f"{RED}警告: これにより、デバイス上のすべてのファイルが消去されます。{RESET}")
    sys.stdout.write("本当によろしいですか？ (y/n): ")
    sys.stdout.flush()
    confirm = getch()
    print(confirm)

    if confirm.lower() == 'y':
        print("ファイルシステムをフォーマット中...")
        response = await run_ble_command(client, "CMD:format_fs:format_now", verbose)
        if response:
            print(f"{GREEN}デバイスからの応答: {response}{RESET}")
            print("デバイスが再起動するため、接続が切断されます。")
        else:
            print(f"{RED}コマンドの送信に失敗しました。{RESET}")
    else:
        print("フォーマットをキャンセルしました。")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='BLE Tool for fastrec device.')
    parser.add_argument('-v', '--verbose', action='store_true', help='Enable verbose output.')
    args = parser.parse_args()
    verbose = args.verbose

    async def main_loop():
        client = None
        try:
            print(f"BLEデバイス '{DEVICE_NAME}' をスキャン中...")
            device = await BleakScanner.find_device_by_name(DEVICE_NAME, timeout=10.0)

            if not device:
                print(f"{RED}エラー: '{DEVICE_NAME}' デバイスが見つかりませんでした。{RESET}")
                print(f"{RED}マイコンの電源が入っているか、BLE設定モードかを確認してください。{RESET}")
                return

            print(f"{GREEN}デバイス発見: {device.address}{RESET}")

            client = BleakClient(device.address)
            print(f"{device.address} に接続中...通知を有効化中...")
            await client.connect()
            await client.start_notify(RESPONSE_UUID, notification_handler)
            print(f"{GREEN}'{RESPONSE_UUID}' の通知を有効化しました。{RESET}")

            while True:
                print("\n--- BLE Tool Menu ---")
                print("1. 録音レコーダに setting.ini を送信")
                print("2. 録音レコーダの setting.ini を表示")
                print("3. 録音レコーダの情報取得")
                print(f"{RED}4. ファイルシステムを初期化{RESET}")
                print("5. ログファイルを取得")
                print("0. 終了")

                sys.stdout.write("Enter your choice: ")
                sys.stdout.flush()
                choice = getch()
                print(choice)

                if choice == '1':
                    try:
                        await send_setting_ini(client, "setting.ini", verbose)
                    except Exception as e:
                        print(f"{RED}Error during send_setting_ini: {e}{RESET}")
                elif choice == '2':
                    try:
                        await get_setting_ini(client, verbose)
                    except Exception as e:
                        print(f"{RED}Error during get_setting_ini: {e}{RESET}")
                elif choice == '3':
                    try:
                        await get_device_info(client, verbose)
                    except Exception as e:
                        print(f"{RED}Error during get_device_info: {e}{RESET}")
                elif choice == '4':
                    try:
                        await format_filesystem(client, verbose)
                        break
                    except Exception as e:
                        print(f"{RED}Error during format_filesystem: {e}{RESET}")
                elif choice == '5':
                    try:
                        await get_log_file(client, verbose)
                    except Exception as e:
                        print(f"{RED}Error during get_log_file: {e}{RESET}")
                elif choice == '0':
                    print("BLEツールを終了します。")
                    break
                else:
                    print(f"{RED}無効な選択です。もう一度お試しください。{RESET}")

        except Exception as e:
            print(f"{RED}致命的なエラーが発生しました: {e}{RESET}")
        finally:
            if client and client.is_connected:
                if verbose:
                    print(f"{GREEN}5. '{RESPONSE_UUID}' の通知を停止し、切断中...{RESET}")
                await client.stop_notify(RESPONSE_UUID)
                await client.disconnect()
                if verbose:
                    print(f"{GREEN}   -> 切断完了。{RESET}")

    asyncio.run(main_loop())