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

# 応答データを受け取るためのグローバル変数とイベント
received_response_data = None
response_event = asyncio.Event() # 応答があったことを通知するためのイベント

# 通知ハンドラ関数
# マイコンから応答特性を通じてデータが送信されると、この関数が呼び出されます。
def notification_handler(characteristic: BleakGATTCharacteristic, data: bytearray):
    global received_response_data
    received_response_data = data.decode('utf-8')
    response_event.set() # 応答があったことをイベントに通知

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
            
# Function to get a single character input without pressing Enter
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

# --- 新しいデータ取得処理 ---
async def run_ble_command(command_str: str, verbose: bool = False):
    global received_response_data
    received_response_data = None # 新しいリクエストごとに応答データをリセット
    response_event.clear() # イベントをクリアして、次の応答を待機できるようにする

    if verbose:
        print(f"\n--- BLEコマンド実行: コマンド='{command_str}' ---")
    if verbose:
        print(f"1. BLEデバイス '{DEVICE_NAME}' をスキャン中...")

    device = await BleakScanner.find_device_by_name(DEVICE_NAME, timeout=10.0)

    if not device:
        print(f"{RED}エラー: '{DEVICE_NAME}' デバイスが見つかりませんでした。{RESET}")
        print(f"{RED}マイコンの電源が入っているか、BLE設定モードかを確認してください。{RESET}")
        return

    if verbose:
        print(f"{GREEN}   -> 発見: {device.address}{RESET}")

    async with BleakClient(device.address) as client:
        if verbose:
            print(f"2. {device.address} に接続し、通知を有効化中...")
        # 応答特性の通知を有効にする
        await client.start_notify(RESPONSE_UUID, notification_handler)
        if verbose:
            print(f"{GREEN}   -> '{RESPONSE_UUID}' の通知を有効化しました。{RESET}")

        # コマンドを送信
        if verbose:
            print(f"3. コマンド '{command_str}' を '{COMMAND_UUID}' に送信中...")
        await client.write_gatt_char(
            COMMAND_UUID,
            bytes(command_str, 'utf-8'),
            response=True # 書き込み応答を要求 (書き込み成功の確認)
        )
        if verbose:
            print(f"{GREEN}   -> コマンド送信完了。応答を待機中...{RESET}")
        try:
            # 応答が来るまで待機 (最大15秒)
            await asyncio.wait_for(response_event.wait(), timeout=15.0)
            if not received_response_data:
                print(f"{RED}4. タイムアウト: 応答データが受信されませんでした。{RESET}")
        except asyncio.TimeoutError:
            print(f"{RED}4. タイムアウト: 応答データが受信されませんでした。マイコンからの応答がありませんでした。{RESET}")
        finally:
            # 通知を停止
            if verbose:
                print(f"{GREEN}5. '{RESPONSE_UUID}' の通知を停止中...{RESET}")
            await client.stop_notify(RESPONSE_UUID)
    return received_response_data # 応答データを返す

async def send_setting_ini(file_path: str, verbose: bool = False):
    try:
        with open(file_path, 'r') as f:
            content = f.read()
        command = f"SET:setting_ini:{content}"
        print(f"送信するsetting.iniの内容:\n{content}")
        print(f"{file_path} から setting.ini を送信中...")
        await run_ble_command(command, verbose)
        print(f"{GREEN}setting.ini を正常に送信しました。{RESET}")
    except FileNotFoundError:
        print(f"{RED}Error: File not found at {file_path}{RESET}")
    except Exception as e:
        if "disconnected" in str(e) and "setting.ini" in file_path:
            print(f"デバイスがリセットされました")
            print(f"「2. デバイスから setting.ini を取得」で反映されたか確認できます")
        else:
            print(f"{RED}Error sending setting.ini: {e}{RESET}")

async def get_setting_ini(verbose: bool = False):
    print("デバイスから setting.ini を要求中...")
    device_response = await run_ble_command("GET:setting_ini", verbose)

    if device_response:
        print(f"\n\n")
        print(f"{GREEN}マイコンのsetting.ini:\n{RESET}{device_response}")

        local_setting_ini_path = "setting.ini" # Assuming local setting.ini is in the same directory
        try:
            with open(local_setting_ini_path, 'r') as f:
                local_content = f.read()
            
            # Compare and print differences
            compare_and_print_diff(device_response, local_content)

        except FileNotFoundError:
            print(f"{RED}ローカルの {local_setting_ini_path} が見つかりませんでした。{RESET}")
        except Exception as e:
            print(f"{RED}ローカルの {local_setting_ini_path} の読み込み中にエラーが発生しました: {e}{RESET}")
    else:
        print(f"{RED}setting.ini の取得に失敗しました。{RESET}")
    return device_response

async def get_device_info(verbose: bool = False):
    print("デバイスから各種情報を要求中...")
    response = await run_ble_command("GET:info", verbose)
    if response:
        if verbose:
            print(f"{GREEN}マイコンからの情報:{RESET}\n{response}")
        try:
            print(f"\n")
            info = json.loads(response)
            print(f"{'バッテリーレベル'} : {int(info.get('battery_level', 0))} %")
            print(f"{'バッテリー電圧'}   : {info.get('battery_voltage', 0.0):.1f} V")
            print(f"{'アプリ状態'}       : {info.get('app_state', 'N/A')}")
            print(f"{'WiFi接続状態'}     : {info.get('wifi_status', 'N/A')}")
            print(f"{'接続済みSSID'}     : {info.get('connected_ssid', 'N/A')}")
            print(f"{'WiFi RSSI'}        : {info.get('wifi_rssi', 'N/A')}")
            print(f"{'LittleFS使用率'}   : {info.get('littlefs_usage_percent', 'N/A')} %")
            
            ls_content = info.get('ls', '')
            if ls_content:
                print(f"{'ディレクトリ一覧'}:")
                for item in ls_content.strip().split('\n'):
                    if item:
                        print(f"  - {item}")
            else:
                print(f"  {'ディレクトリ一覧'}: N/A")
        except json.JSONDecodeError:
            print(f"{RED}エラー: 受信した情報がJSON形式ではありません。{RESET}")
    else:
        print(f"{RED}各種情報の取得に失敗しました。{RESET}")
    return response

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='BLE Tool for fastrec device.')
    parser.add_argument('-v', '--verbose', action='store_true', help='Enable verbose output.')
    args = parser.parse_args()
    verbose = args.verbose

    try:
        while True:
            print("\n--- BLE Tool Menu ---")
            print("1. 録音レコーダに setting.ini を送信")
            print("2. 録音レコーダの setting.ini を表示")
            print("3. 録音レコーダの情報取得")
            print("0. 終了")

            sys.stdout.write("Enter your choice: ")
            sys.stdout.flush()
            choice = getch()
            print(choice) # Echo the choice back to the user

            if choice == '1':
                try:
                    asyncio.run(send_setting_ini("setting.ini", verbose))
                except Exception as e:
                    print(f"{RED}Error during send_setting_ini: {e}{RESET}")
            elif choice == '2':
                try:
                    asyncio.run(get_setting_ini(verbose))
                except Exception as e:
                    print(f"{RED}Error during get_setting_ini: {e}{RESET}")
            elif choice == '3':
                try:
                    asyncio.run(get_device_info(verbose))
                except Exception as e:
                    print(f"{RED}Error during get_littlefs_ls: {e}{RESET}")
            elif choice == '0':
                print("BLEツールを終了します。")
                break
            else:
                print(f"{RED}無効な選択です。もう一度お試しください。{RESET}")

    except Exception as e:
        print(f"{RED}致命的なエラーが発生しました: {e}{RESET}")
