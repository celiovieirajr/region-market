// View: manipulação do DOM da tela de chat
const ChatView = (() => {
    const messagesEl = document.getElementById('messages');
    const form = document.getElementById('chat-form');
    const input = document.getElementById('message-input');
    const sendBtn = document.getElementById('send-btn');
    const clearBtn = document.getElementById('clear-btn');
    const logoutBtn = document.getElementById('logout-btn');
    const currentUserEl = document.getElementById('current-user');
    const typingIndicator = document.getElementById('typing-indicator');
    const guestLabel = document.getElementById('guest-label');
    const userLabel = document.getElementById('user-label');
    const guestLoginLink = document.getElementById('guest-login-link');
    const settingsLink = document.getElementById('settings-link');
    const muralLink = document.getElementById('mural-link');
    const clearModal = document.getElementById('clear-confirm-modal');
    const clearCancelBtn = document.getElementById('clear-cancel-btn');
    const clearConfirmBtn = document.getElementById('clear-confirm-btn');
    const citySelect = document.getElementById('city-select');

    function setLoggedInState(username, isAdmin) {
        currentUserEl.textContent = username;
        guestLabel.classList.add('hidden');
        userLabel.classList.remove('hidden');
        guestLoginLink.classList.add('hidden');
        clearBtn.classList.remove('hidden');
        settingsLink.classList.toggle('hidden', !isAdmin);
        muralLink.classList.toggle('hidden', !isAdmin);
        logoutBtn.classList.remove('hidden');
        citySelect.classList.remove('hidden');
    }

    function setGuestState() {
        guestLabel.classList.remove('hidden');
        userLabel.classList.add('hidden');
        guestLoginLink.classList.remove('hidden');
        clearBtn.classList.add('hidden');
        settingsLink.classList.add('hidden');
        muralLink.classList.add('hidden');
        logoutBtn.classList.add('hidden');
        citySelect.classList.add('hidden');
    }

    function renderCityOptions(cities, currentCity) {
        const options = ['<option value="">Selecionar cidade</option>']
            .concat(cities.map((c) => `<option value="${escapeHtml(c)}">${escapeHtml(c)}</option>`));
        citySelect.innerHTML = options.join('');
        citySelect.value = currentCity || '';
    }

    function onCityChange(handler) {
        citySelect.addEventListener('change', () => handler(citySelect.value));
    }

    function escapeHtml(str) {
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    function clearMessages() {
        messagesEl.innerHTML = '';
    }

    function renderMessage(role, content, elapsedSeconds) {
        const isUser = role === 'USER';
        const wrapper = document.createElement('div');
        wrapper.className = `flex flex-col ${isUser ? 'items-end' : 'items-start'}`;

        const bubble = document.createElement('div');
        bubble.className = `chat-bubble rounded-2xl px-4 py-2.5 text-sm shadow-sm ${
            isUser
                ? 'bg-indigo-600 text-white rounded-br-sm'
                : 'bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 border border-slate-200 dark:border-slate-700 rounded-bl-sm'
        }`;
        bubble.textContent = content;
        wrapper.appendChild(bubble);

        if (!isUser && typeof elapsedSeconds === 'number') {
            const timing = document.createElement('span');
            timing.className = 'text-xs text-slate-400 dark:text-slate-500 mt-1 px-1';
            timing.textContent = `⏱ ${elapsedSeconds.toFixed(1)}s`;
            wrapper.appendChild(timing);
        }

        messagesEl.appendChild(wrapper);
        scrollToBottom();
    }

    function renderHistory(history) {
        messagesEl.innerHTML = '';
        if (!history || history.length === 0) {
            renderEmptyState();
            return;
        }
        history.forEach((msg) => renderMessage(msg.role, msg.content));
    }

    function renderEmptyState() {
        messagesEl.innerHTML = `
            <div class="text-center text-slate-400 dark:text-slate-500 text-sm mt-10">
                Envie uma mensagem para começar a conversa 👋
            </div>`;
    }

    function clearInput() {
        input.value = '';
        input.focus();
    }

    let typingTimerInterval = null;
    let typingStartedAt = null;

    function setSending(isSending) {
        sendBtn.disabled = isSending;
        input.disabled = isSending;
        typingIndicator.classList.toggle('hidden', !isSending);

        if (isSending) {
            typingStartedAt = performance.now();
            updateTypingTimerText();
            typingTimerInterval = setInterval(updateTypingTimerText, 100);
        } else {
            clearInterval(typingTimerInterval);
            typingTimerInterval = null;
        }
    }

    function updateTypingTimerText() {
        const elapsed = (performance.now() - typingStartedAt) / 1000;
        typingIndicator.textContent = `IA está digitando... (${elapsed.toFixed(1)}s)`;
    }

    function scrollToBottom() {
        messagesEl.scrollTop = messagesEl.scrollHeight;
    }

    function onSend(handler) {
        form.addEventListener('submit', (e) => {
            e.preventDefault();
            const message = input.value.trim();
            if (!message) return;
            handler(message);
        });
    }

    function onClear(handler) {
        clearBtn.addEventListener('click', () => clearModal.classList.remove('hidden'));
        clearCancelBtn.addEventListener('click', () => clearModal.classList.add('hidden'));
        clearModal.addEventListener('click', (e) => {
            if (e.target === clearModal) clearModal.classList.add('hidden');
        });
        clearConfirmBtn.addEventListener('click', () => {
            clearModal.classList.add('hidden');
            handler();
        });
    }

    function onLogout(handler) {
        logoutBtn.addEventListener('click', handler);
    }

    return {
        setLoggedInState,
        setGuestState,
        clearMessages,
        renderMessage,
        renderHistory,
        renderEmptyState,
        clearInput,
        setSending,
        onSend,
        onClear,
        onLogout,
        renderCityOptions,
        onCityChange,
    };
})();
