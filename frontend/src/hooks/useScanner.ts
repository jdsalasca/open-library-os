// Reads a copy code from whatever the desk has: a USB scanner that behaves like a
// keyboard, the phone camera, or a human typing. One hook, three inputs.
//
// A wedge scanner is not a special device: it types fast and presses Enter. So the
// only thing to detect is "a burst of characters that ends in Enter", which is why
// this needs no library at all. The camera is the only part that needs the platform.
import { useCallback, useEffect, useRef, useState } from 'react';

/** Fast typing with a terminator is a scanner; a human types slower. */
const WEDGE_MAX_GAP_MS = 60;
const WEDGE_MIN_LENGTH = 6;

type BarcodeDetectorLike = {
  detect: (source: CanvasImageSource) => Promise<Array<{ rawValue: string }>>;
};

function nativeDetector(): BarcodeDetectorLike | null {
  const ctor = (window as unknown as { BarcodeDetector?: new () => BarcodeDetectorLike })
    .BarcodeDetector;
  if (!ctor) return null;
  try {
    return new ctor();
  } catch {
    return null;
  }
}

export function useScanner(onCode: (code: string) => void) {
  const [wedgeActive, setWedgeActive] = useState(false);
  const [cameraError, setCameraError] = useState<string | null>(null);
  const [cameraReady, setCameraReady] = useState(false);
  const handler = useRef(onCode);
  // Kept fresh without re-binding the listener on every render.
  useEffect(() => {
    handler.current = onCode;
  }, [onCode]);

  // ── keyboard wedge (USB scanner) ──────────────────────────────────────────
  useEffect(() => {
    let buffer = '';
    let last = 0;

    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      // Never steal keys from a field the librarian is typing into.
      if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return;

      if (event.key === 'Enter') {
        if (buffer.length >= WEDGE_MIN_LENGTH) {
          event.preventDefault();
          handler.current(buffer.trim());
        }
        buffer = '';
        setWedgeActive(false);
        return;
      }
      if (event.key.length !== 1) return;

      const now = Date.now();
      if (now - last > WEDGE_MAX_GAP_MS) buffer = '';
      last = now;
      buffer += event.key;
      setWedgeActive(true);
    };

    window.addEventListener('keydown', onKeyDown, true);
    return () => window.removeEventListener('keydown', onKeyDown, true);
  }, []);

  // ── camera ───────────────────────────────────────────────────────────────
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);

  const stopCamera = useCallback(() => {
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    setCameraReady(false);
  }, []);

  const startCamera = useCallback(async () => {
    setCameraError(null);
    const detector = nativeDetector();
    if (!detector) {
      setCameraError(
        'Este navegador no puede leer codigos con la camara. Usa un escaner USB o escribe el codigo.',
      );
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: 'environment' },
      });
      streamRef.current = stream;
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        await videoRef.current.play();
      }
      setCameraReady(true);

      const tick = async () => {
        if (!streamRef.current || !videoRef.current) return;
        try {
          const found = await detector.detect(videoRef.current);
          const value = found[0]?.rawValue;
          if (value) {
            handler.current(value.trim());
            stopCamera();
            return;
          }
        } catch {
          // A frame that cannot be decoded is normal; keep scanning.
        }
        window.setTimeout(tick, 250);
      };
      void tick();
    } catch {
      setCameraError('No se pudo abrir la camara. Revisa los permisos del navegador.');
    }
  }, [stopCamera]);

  useEffect(() => stopCamera, [stopCamera]);

  return { wedgeActive, cameraError, cameraReady, videoRef, startCamera, stopCamera };
}
