package com.gamtan.frame;

import android.app.admin.DeviceAdminReceiver;

/**
 * 감탄 액자를 키오스크 "기기 주인"으로 쓰기 위한 관리자 수신기.
 * 출고 세팅 때: adb shell dpm set-device-owner com.gamtan.frame/.AdminReceiver
 * 그러면 앱이 위에서 끌어내리는 알림창(설정 버튼 포함)을 꺼 둘 수 있음.
 */
public class AdminReceiver extends DeviceAdminReceiver {
}
