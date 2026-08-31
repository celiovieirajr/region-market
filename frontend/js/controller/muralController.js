// Controller: guarda de acesso da página Mural (só ADMIN)
(() => {
    if (!ApiModel.isAuthenticated() || !ApiModel.isAdmin()) {
        window.location.href = '/index.html';
        return;
    }

    document.getElementById('current-user').textContent = ApiModel.getUsername();
    document.getElementById('logout-btn').addEventListener('click', () => ApiModel.logout());
})();
