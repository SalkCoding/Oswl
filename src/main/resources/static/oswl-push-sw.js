self.addEventListener('push', event => {
    let data;
    try { data = event.data.json(); } catch (_) { return; }
    const path = /^\/projects\/\d+\/security-center$/.test(data.url) ? data.url : '/projects';
    event.waitUntil(self.registration.showNotification('OsWL', {
        body: typeof data.body === 'string' ? data.body.slice(0, 300) : '',
        tag: typeof data.tag === 'string' ? data.tag.slice(0, 100) : 'oswl-security',
        data: {path}, icon: '/graphic/symbol_w.svg'
    }));
});
self.addEventListener('notificationclick', event => {
    event.notification.close();
    const path = event.notification.data && event.notification.data.path;
    const destination = new URL(/^\/projects\/\d+\/security-center$/.test(path) ? path : '/projects', self.location.origin).href;
    event.waitUntil(clients.openWindow(destination));
});
