/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_QUEUE_ENABLED?: string;
  readonly VITE_DEMO_MODE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
