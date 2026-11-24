const CACHE_NAME = 'phonedrop-v7';
const STATIC_ASSETS = [
  '/',
  '/style.css',
  '/app.js',
  '/site.webmanifest',
  '/favicon.ico',
  '/icon-192.png',
  '/icon-512.png',
  '/icon.svg'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll(STATIC_ASSETS);
    })
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.map((key) => {
          if (key !== CACHE_NAME) {
            return caches.delete(key);
          }
        })
      );
    })
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  // Hanya GET yang di-cache. POST (upload, Share Target) langsung diteruskan ke server
  // tanpa campur tangan Service Worker agar file besar di-stream apa adanya.
  if (event.request.method !== 'GET') {
    return;
  }

  // Jangan pernah cache API, WebSocket, Share Target, file sertifikat, atau installer APK
  if (
    url.pathname.startsWith('/api/') ||
    url.pathname.startsWith('/ws') ||
    url.pathname.startsWith('/share-target') ||
    url.pathname.endsWith('.crt') ||
    url.pathname.endsWith('.apk')
  ) {
    return;
  }

  // Network-first dengan fallback ke Cache untuk aset UI.
  // Query string (mis. ?shared=files) diabaikan saat mencari cache.
  event.respondWith(
    fetch(event.request)
      .then((networkResponse) => {
        if (networkResponse && networkResponse.status === 200 && url.search === '') {
          const responseClone = networkResponse.clone();
          caches.open(CACHE_NAME).then((cache) => {
            cache.put(event.request, responseClone);
          });
        }
        return networkResponse;
      })
      .catch(() => caches.match(event.request, { ignoreSearch: true }))
  );
});
