import { useKakaoLoader } from 'react-kakao-maps-sdk'

/**
 * Kakao JavaScript 키 (Kakao Developers > 앱 > JavaScript 키)
 * Vite 는 VITE_ 접두사 환경변수만 클라이언트에 노출합니다.
 */
export function getKakaoMapKey(): string {
  return (import.meta.env.VITE_KAKAO_MAP_KEY ?? '').trim()
}

export function formatKakaoError(error: unknown): string {
  if (!getKakaoMapKey()) {
    return 'VITE_KAKAO_MAP_KEY 가 비어 있어요. FE/.env.local 에 JavaScript 키를 넣고 dev 서버를 재시작하세요.'
  }
  const isLoadEvent =
    (typeof Event !== 'undefined' && error instanceof Event) ||
    String(error ?? '') === '[object Event]'

  if (isLoadEvent) {
    return '카카오맵 SDK 로딩에 실패했어요. Kakao Developers에서 JavaScript 키를 사용했는지, Web 플랫폼 도메인에 http://localhost:5173 과 http://127.0.0.1:5173 이 등록됐는지 확인한 뒤 dev 서버를 재시작하세요.'
  }

  const message =
    error instanceof Error
      ? error.message
      : typeof error === 'object' && error && 'message' in error
        ? String((error as { message?: unknown }).message ?? '')
        : String(error ?? '')

  if (/appkey|app key|unauthorized|401|403|invalid/i.test(message)) {
    return '카카오 앱 키가 올바르지 않거나 권한이 없습니다. JavaScript 키인지, Web 플랫폼 도메인에 http://localhost:5173 과 http://127.0.0.1:5173 이 등록됐는지 확인하세요.'
  }
  if (message) {
    return message
  }
  return '카카오맵 SDK를 불러오지 못했습니다. 네트워크 또는 키/도메인 설정을 확인하세요.'
}

/**
 * 앱 전체에서 동일한 옵션으로 Kakao Maps SDK를 로드한다.
 * react-kakao-maps-sdk Loader 는 싱글턴이므로 반드시 이 훅만 사용한다.
 *
 * @returns [loading, error]
 */
export function useKakao(): [boolean, Error | undefined] {
  const appkey = getKakaoMapKey()

  // hooks 규칙은 항상 동일 순서로 호출 — 키가 없어도 Loader 를 호출하되 즉시 대체 에러 반환
  const [loading, loaderError] = useKakaoLoader({
    appkey: appkey || 'MISSING_KAKAO_APP_KEY',
    libraries: ['services'],
    // 기본 URL(//dapi.kakao.com/v2/maps/sdk.js) 사용 — 커스텀 https url 제거
  })

  if (!appkey) {
    return [
      false,
      new Error(
        'VITE_KAKAO_MAP_KEY 미설정. FE/Myeongrang-hoe/.env.local 에 JavaScript 키를 추가한 뒤 npm run dev 를 재시작하세요.',
      ),
    ]
  }

  if (loaderError) {
    const err =
      loaderError instanceof Error
        ? loaderError
        : new Error(formatKakaoError(loaderError))
    return [false, err]
  }

  return [loading, undefined]
}

/** 지도 컨테이너 크기 변경 후 타일이 깨질 때 호출 */
export function relayoutMap(map: kakao.maps.Map | null | undefined) {
  if (!map) return
  try {
    map.relayout()
  } catch {
    // ignore
  }
}
