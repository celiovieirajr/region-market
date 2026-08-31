// Controller: alterna tema claro/escuro (disponível para visitantes e logados)
(() => {
    const KEY = 'chatbot_theme';
    const toggleBtn = document.getElementById('theme-toggle');
    if (!toggleBtn) return;

    function isDark() {
        return document.documentElement.classList.contains('dark');
    }

    function updateIcon() {
        toggleBtn.textContent = isDark() ? '☀️' : '🌙';
    }

    toggleBtn.addEventListener('click', () => {
        const next = !isDark();
        document.documentElement.classList.toggle('dark', next);
        localStorage.setItem(KEY, next ? 'dark' : 'light');
        updateIcon();
    });

    updateIcon();
})();
