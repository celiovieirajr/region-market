// Controller: liga o LoginView ao ApiModel
(() => {
    if (ApiModel.isAuthenticated()) {
        window.location.href = '/index.html';
        return;
    }

    LoginView.onSubmit(async (username, password) => {
        LoginView.hideError();

        if (!username || !password) {
            LoginView.showError('Preencha usuário e senha.');
            return;
        }

        LoginView.setLoading(true);
        try {
            await ApiModel.login(username, password);
            window.location.href = '/index.html';
        } catch (err) {
            LoginView.showError(err.message || 'Falha ao entrar.');
        } finally {
            LoginView.setLoading(false);
        }
    });
})();
