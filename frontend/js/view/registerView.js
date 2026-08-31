// View: manipulação do DOM do modal de criação de conta (forçado ao enviar 1ª mensagem)
const RegisterView = (() => {
    const modal = document.getElementById('register-modal');
    const form = document.getElementById('register-form');
    const usernameInput = document.getElementById('register-username');
    const passwordInput = document.getElementById('register-password');
    const confirmInput = document.getElementById('register-password-confirm');
    const errorEl = document.getElementById('register-error');
    const submitBtn = document.getElementById('register-btn');
    const cancelBtn = document.getElementById('register-cancel-btn');

    function open() {
        modal.classList.remove('hidden');
        usernameInput.focus();
    }

    function close() {
        modal.classList.add('hidden');
        hideError();
        form.reset();
    }

    function onSubmit(handler) {
        form.addEventListener('submit', (e) => {
            e.preventDefault();
            handler(usernameInput.value.trim(), passwordInput.value, confirmInput.value);
        });
    }

    function onCancel(handler) {
        cancelBtn.addEventListener('click', handler);
        modal.addEventListener('click', (e) => {
            if (e.target === modal) handler();
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
        submitBtn.disabled = isLoading;
        submitBtn.textContent = isLoading ? 'Criando conta...' : 'Criar conta e enviar';
    }

    return { open, close, onSubmit, onCancel, showError, hideError, setLoading };
})();
