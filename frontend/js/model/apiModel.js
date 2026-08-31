// Model: comunicação com API e acesso a dados de sessão (token, usuário)
const ApiModel = (() => {
    // Backend roda em servidor/porta separados do frontend — ajuste aqui se mudar de máquina/porta.
    const BASE_URL = 'http://localhost:8080/api';
    const TOKEN_KEY = 'chatbot_token';
    const USER_KEY = 'chatbot_user';
    const ROLE_KEY = 'chatbot_role';

    function getToken() {
        return localStorage.getItem(TOKEN_KEY);
    }

    function getUsername() {
        return localStorage.getItem(USER_KEY);
    }

    function getRole() {
        return localStorage.getItem(ROLE_KEY);
    }

    function isAdmin() {
        return getRole() === 'ADMIN';
    }

    function saveSession(token, username, role) {
        localStorage.setItem(TOKEN_KEY, token);
        localStorage.setItem(USER_KEY, username);
        localStorage.setItem(ROLE_KEY, role);
    }

    function clearSession() {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_KEY);
        localStorage.removeItem(ROLE_KEY);
    }

    function isAuthenticated() {
        return !!getToken();
    }

    async function request(path, options = {}) {
        const headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
        const token = getToken();
        if (token) {
            headers['Authorization'] = `Bearer ${token}`;
        }

        const response = await fetch(BASE_URL + path, Object.assign({}, options, { headers }));

        const isAuthEndpoint = path.startsWith('/auth/');
        if (response.status === 401 && !isAuthEndpoint) {
            clearSession();
            window.location.href = '/index.html';
            throw new Error('Sessão expirada. Faça login novamente.');
        }

        if (!response.ok) {
            let message = 'Erro na requisição';
            try {
                const body = await response.json();
                message = body.message || message;
            } catch (_) {
                // ignore parse errors
            }
            throw new Error(message);
        }

        if (response.status === 204) {
            return null;
        }

        return response.json();
    }

    async function login(username, password) {
        const data = await request('/auth/login', {
            method: 'POST',
            body: JSON.stringify({ username, password }),
        });
        saveSession(data.token, data.username, data.role);
        return data;
    }

    async function register(username, password) {
        const data = await request('/auth/register', {
            method: 'POST',
            body: JSON.stringify({ username, password }),
        });
        saveSession(data.token, data.username, data.role);
        return data;
    }

    function logout() {
        clearSession();
        window.location.href = '/index.html';
    }

    async function getHistory() {
        return request('/chat/history', { method: 'GET' });
    }

    async function sendMessage(message) {
        return request('/chat/send', {
            method: 'POST',
            body: JSON.stringify({ message }),
        });
    }

    async function clearHistory() {
        return request('/chat/clear', { method: 'DELETE' });
    }

    async function getSources() {
        return request('/settings/sources', { method: 'GET' });
    }

    async function addSource(city, label, url) {
        return request('/settings/sources', {
            method: 'POST',
            body: JSON.stringify({ city, label, url }),
        });
    }

    async function deleteSource(id) {
        return request(`/settings/sources/${id}`, { method: 'DELETE' });
    }

    async function updateSourceCache(id, { cacheEnabled, cacheTtlHours }) {
        return request(`/settings/sources/${id}`, {
            method: 'PUT',
            body: JSON.stringify({ cacheEnabled, cacheTtlHours }),
        });
    }

    async function refreshSourceCache(id) {
        return request(`/settings/sources/${id}/cache/refresh`, { method: 'POST' });
    }

    async function getCities() {
        return request('/cities', { method: 'GET' });
    }

    async function getMe() {
        return request('/user/me', { method: 'GET' });
    }

    async function setCity(city) {
        return request('/user/city', {
            method: 'PUT',
            body: JSON.stringify({ city }),
        });
    }

    return {
        login,
        register,
        logout,
        getHistory,
        sendMessage,
        clearHistory,
        getSources,
        addSource,
        deleteSource,
        updateSourceCache,
        refreshSourceCache,
        getCities,
        getMe,
        setCity,
        isAuthenticated,
        getUsername,
        getRole,
        isAdmin,
    };
})();
