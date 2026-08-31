// View: manipulação do DOM da página de Configurações
const SettingsView = (() => {
    const form = document.getElementById('source-form');
    const cityInput = document.getElementById('source-city');
    const labelInput = document.getElementById('source-label');
    const urlInput = document.getElementById('source-url');
    const errorEl = document.getElementById('source-error');
    const listEl = document.getElementById('source-list');
    const currentUserEl = document.getElementById('current-user');
    const logoutBtn = document.getElementById('logout-btn');

    function setCurrentUser(username) {
        currentUserEl.textContent = username;
    }

    function onSubmit(handler) {
        form.addEventListener('submit', (e) => {
            e.preventDefault();
            handler(cityInput.value.trim(), labelInput.value.trim(), urlInput.value.trim());
        });
    }

    function onDelete(handler) {
        listEl.addEventListener('click', (e) => {
            const btn = e.target.closest('[data-delete-id]');
            if (!btn) return;
            handler(Number(btn.dataset.deleteId));
        });
    }

    function onCacheToggle(handler) {
        listEl.addEventListener('change', (e) => {
            const input = e.target.closest('[data-cache-toggle-id]');
            if (!input) return;
            handler(Number(input.dataset.cacheToggleId), input.checked);
        });
    }

    function onCacheTtlChange(handler) {
        function commit(input) {
            const id = Number(input.dataset.cacheTtlId);
            const raw = input.value.trim();
            const value = Number(raw);
            if (!raw || !Number.isInteger(value) || value <= 0) {
                showError('TTL do cache deve ser um número inteiro positivo (horas).');
                input.value = input.dataset.lastValidValue || '1';
                return;
            }
            input.dataset.lastValidValue = String(value);
            handler(id, value);
        }

        listEl.addEventListener('focusin', (e) => {
            const input = e.target.closest('[data-cache-ttl-id]');
            if (!input) return;
            input.dataset.lastValidValue = input.value;
        });

        listEl.addEventListener('keydown', (e) => {
            const input = e.target.closest('[data-cache-ttl-id]');
            if (!input) return;
            if (e.key === 'Enter') {
                e.preventDefault();
                input.blur();
            }
        });

        listEl.addEventListener('focusout', (e) => {
            const input = e.target.closest('[data-cache-ttl-id]');
            if (!input) return;
            commit(input);
        });
    }

    function onCacheRefresh(handler) {
        listEl.addEventListener('click', (e) => {
            const btn = e.target.closest('[data-cache-refresh-id]');
            if (!btn) return;
            handler(Number(btn.dataset.cacheRefreshId));
        });
    }

    function setRefreshLoading(id, isLoading) {
        const btn = listEl.querySelector(`[data-cache-refresh-id="${id}"]`);
        if (!btn) return;
        btn.disabled = isLoading;
        btn.textContent = isLoading ? 'Atualizando...' : 'Atualizar agora';
        btn.classList.toggle('opacity-60', isLoading);
        btn.classList.toggle('cursor-not-allowed', isLoading);
    }

    function onLogout(handler) {
        logoutBtn.addEventListener('click', handler);
    }

    function clearForm() {
        form.reset();
        cityInput.focus();
    }

    function showError(message) {
        errorEl.textContent = message;
        errorEl.classList.remove('hidden');
    }

    function hideError() {
        errorEl.classList.add('hidden');
    }

    function renderList(sources) {
        if (!sources || sources.length === 0) {
            listEl.innerHTML = '<p class="text-sm text-slate-400 dark:text-slate-500">Nenhum site cadastrado ainda.</p>';
            return;
        }

        const groups = new Map();
        sources.forEach((s) => {
            if (!groups.has(s.city)) groups.set(s.city, []);
            groups.get(s.city).push(s);
        });

        const sortedCities = [...groups.keys()].sort((a, b) => a.localeCompare(b, 'pt-BR'));

        listEl.innerHTML = sortedCities
            .map((city) => {
                const items = groups.get(city);
                const rows = items
                    .map(
                        (s) => `
                        <li class="flex flex-col gap-2 px-3 py-2">
                            <div class="flex items-center justify-between gap-2">
                                <div class="min-w-0">
                                    <p class="text-sm font-medium text-slate-700 dark:text-slate-200 truncate">${escapeHtml(s.label)}</p>
                                    <p class="text-xs text-slate-400 dark:text-slate-500 truncate">${escapeHtml(s.url)}</p>
                                </div>
                                <button data-delete-id="${s.id}" title="Remover"
                                        class="shrink-0 text-red-500 dark:text-red-400 hover:text-red-700 dark:hover:text-red-300 text-sm px-2 py-1 rounded hover:bg-red-50 dark:hover:bg-red-950">
                                    Remover
                                </button>
                            </div>
                            <div class="flex flex-wrap items-center gap-3 bg-slate-100 dark:bg-slate-800/60 rounded-lg px-3 py-2">
                                <label class="flex items-center gap-1.5 text-xs text-slate-600 dark:text-slate-300 cursor-pointer select-none">
                                    <input type="checkbox" data-cache-toggle-id="${s.id}" ${s.cacheEnabled ? 'checked' : ''}
                                           class="rounded border-slate-300 dark:border-slate-600 text-indigo-600 focus:ring-indigo-500 dark:bg-slate-700" />
                                    Cache habilitado
                                </label>
                                <label class="flex items-center gap-1.5 text-xs text-slate-600 dark:text-slate-300">
                                    TTL (horas)
                                    <input type="number" min="1" step="1" data-cache-ttl-id="${s.id}" value="${Number(s.cacheTtlHours) || 1}"
                                           class="w-16 rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 px-2 py-1 text-xs focus:outline-none focus:ring-2 focus:ring-indigo-500" />
                                </label>
                                <button type="button" data-cache-refresh-id="${s.id}"
                                        class="text-xs px-2 py-1 rounded-lg border border-indigo-300 dark:border-indigo-700 text-indigo-600 dark:text-indigo-300 hover:bg-indigo-50 dark:hover:bg-indigo-950 transition-colors disabled:opacity-60 disabled:cursor-not-allowed">
                                    Atualizar agora
                                </button>
                                <span data-cache-status-id="${s.id}" class="text-xs text-slate-400 dark:text-slate-500 ${s.cacheEnabled ? '' : 'hidden'}">
                                    ${escapeHtml(formatCacheStatus(s.cacheLastUpdatedAt))}
                                </span>
                            </div>
                        </li>`
                    )
                    .join('');

                return `
                    <div class="border border-slate-200 dark:border-slate-600 rounded-lg overflow-hidden">
                        <div class="bg-slate-100 dark:bg-slate-700 px-3 py-2 flex items-center justify-between">
                            <span class="text-sm font-semibold text-slate-700 dark:text-slate-200">📍 ${escapeHtml(city)}</span>
                            <span class="text-xs text-slate-400 dark:text-slate-400">${items.length} site${items.length > 1 ? 's' : ''}</span>
                        </div>
                        <ul class="divide-y divide-slate-200 dark:divide-slate-600 bg-slate-50 dark:bg-slate-700/50">
                            ${rows}
                        </ul>
                    </div>`;
            })
            .join('');
    }

    function escapeHtml(str) {
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    function formatCacheStatus(cacheLastUpdatedAt) {
        if (!cacheLastUpdatedAt) {
            return 'Cache nunca atualizado';
        }
        const date = new Date(cacheLastUpdatedAt);
        if (Number.isNaN(date.getTime())) {
            return 'Cache nunca atualizado';
        }
        const diffMs = Date.now() - date.getTime();
        const diffMin = Math.round(diffMs / 60000);
        let relative;
        if (diffMin < 1) {
            relative = 'agora mesmo';
        } else if (diffMin < 60) {
            relative = `há ${diffMin} min`;
        } else if (diffMin < 60 * 24) {
            relative = `há ${Math.round(diffMin / 60)}h`;
        } else {
            relative = `há ${Math.round(diffMin / (60 * 24))}d`;
        }
        const formattedDate = date.toLocaleString('pt-BR');
        return `Cache atualizado ${relative} (${formattedDate})`;
    }

    return {
        setCurrentUser,
        onSubmit,
        onDelete,
        onLogout,
        clearForm,
        showError,
        hideError,
        renderList,
        onCacheToggle,
        onCacheTtlChange,
        onCacheRefresh,
        setRefreshLoading,
    };
})();
