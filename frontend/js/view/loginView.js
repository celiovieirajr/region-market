// View: manipulação do DOM da tela de login
const LoginView = (() => {
    const form = document.getElementById('login-form');
    const usernameInput = document.getElementById('username');
    const passwordInput = document.getElementById('password');
    const errorEl = document.getElementById('login-error');
    const loginBtn = document.getElementById('login-btn');

    function onSubmit(handler) {
        form.addEventListener('submit', (e) => {
            e.preventDefault();
            handler(usernameInput.value.trim(), passwordInput.value);
        });
    }

    function showError(message) {
        errorEl.textContent = message;
        errorEl.classList.remove('hidden');
    }

    function hideError() {
        errorEl.classList.add('hidden');
    }

    function setLoading(isLoading) {
        loginBtn.disabled = isLoading;
        loginBtn.textContent = isLoading ? 'Entrando...' : 'Entrar';
    }

    return { onSubmit, showError, hideError, setLoading };
})();
