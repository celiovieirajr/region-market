// Controller: liga o ChatView/RegisterView ao ApiModel.
// Tela pública: visitante pode digitar, mas ao enviar a 1ª mensagem é
// obrigado a criar uma conta grátis antes da IA responder.
(() => {
    let pendingMessage = null;

    function refreshHeader() {
        if (ApiModel.isAuthenticated()) {
            ChatView.setLoggedInState(ApiModel.getUsername(), ApiModel.isAdmin());
            loadCitySelector();
        } else {
            ChatView.setGuestState();
        }
    }

    async function loadCitySelector() {
        try {
            const [cities, profile] = await Promise.all([ApiModel.getCities(), ApiModel.getMe()]);
            ChatView.renderCityOptions(cities, profile.city);
        } catch (err) {
            console.error(err);
        }
    }

    async function loadHistory() {
        if (!ApiModel.isAuthenticated()) {
            ChatView.renderEmptyState();
            return;
        }
        try {
            const history = await ApiModel.getHistory();
            ChatView.renderHistory(history);
        } catch (err) {
            console.error(err);
        }
    }

    async function performSend(message) {
        ChatView.renderMessage('USER', message);
        ChatView.clearInput();
        ChatView.setSending(true);
        const startedAt = performance.now();
        try {
            const reply = await ApiModel.sendMessage(message);
            const elapsedSeconds = (performance.now() - startedAt) / 1000;
            ChatView.renderMessage('ASSISTANT', reply.content, elapsedSeconds);
        } catch (err) {
            const elapsedSeconds = (performance.now() - startedAt) / 1000;
            ChatView.renderMessage('ASSISTANT', 'Erro ao obter resposta: ' + err.message, elapsedSeconds);
        } finally {
            ChatView.setSending(false);
        }
    }

    ChatView.onSend((message) => {
        if (!ApiModel.isAuthenticated()) {
            pendingMessage = message;
            RegisterView.open();
            return;
        }
        performSend(message);
    });

    ChatView.onClear(async () => {
        try {
            await ApiModel.clearHistory();
            ChatView.renderEmptyState();
        } catch (err) {
            alert('Erro ao limpar contexto: ' + err.message);
        }
    });

    ChatView.onLogout(() => {
        ApiModel.logout();
    });

    ChatView.onCityChange(async (city) => {
        try {
            await ApiModel.setCity(city);
        } catch (err) {
            alert('Erro ao definir cidade: ' + err.message);
        }
    });

    RegisterView.onSubmit(async (username, password, confirmPassword) => {
        RegisterView.hideError();

        if (!username || !password || !confirmPassword) {
            RegisterView.showError('Preencha todos os campos.');
            return;
        }
        if (password.length < 6) {
            RegisterView.showError('A senha deve ter no mínimo 6 caracteres.');
            return;
        }
        if (password !== confirmPassword) {
            RegisterView.showError('As senhas não coincidem.');
            return;
        }

        RegisterView.setLoading(true);
        try {
            await ApiModel.register(username, password);
            RegisterView.close();
            refreshHeader();
            ChatView.clearMessages();

            const message = pendingMessage;
            pendingMessage = null;
            if (message) {
                await performSend(message);
            }
        } catch (err) {
            RegisterView.showError(err.message || 'Erro ao criar conta.');
        } finally {
            RegisterView.setLoading(false);
        }
    });

    RegisterView.onCancel(() => {
        RegisterView.close();
        pendingMessage = null;
    });

    refreshHeader();
    loadHistory();
})();
