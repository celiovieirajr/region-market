// Controller: liga o SettingsView ao ApiModel (página própria de Configurações)
(() => {
    if (!ApiModel.isAuthenticated() || !ApiModel.isAdmin()) {
        window.location.href = '/index.html';
        return;
    }

    SettingsView.setCurrentUser(ApiModel.getUsername());

    let currentSources = [];

    async function loadSources() {
        try {
            const sources = await ApiModel.getSources();
            currentSources = sources || [];
            SettingsView.renderList(sources);
        } catch (err) {
            SettingsView.showError(err.message || 'Erro ao carregar sites.');
        }
    }

    SettingsView.onSubmit(async (city, label, url) => {
        SettingsView.hideError();
        if (!city || !label || !url) {
            SettingsView.showError('Preencha cidade, nome do site e URL.');
            return;
        }
        try {
            await ApiModel.addSource(city, label, url);
            SettingsView.clearForm();
            loadSources();
        } catch (err) {
            SettingsView.showError(err.message || 'Erro ao adicionar site.');
        }
    });

    SettingsView.onDelete(async (id) => {
        try {
            await ApiModel.deleteSource(id);
            loadSources();
        } catch (err) {
            SettingsView.showError(err.message || 'Erro ao remover site.');
        }
    });

    function findSource(id) {
        return currentSources.find((s) => s.id === id);
    }

    SettingsView.onCacheToggle(async (id, cacheEnabled) => {
        SettingsView.hideError();
        const source = findSource(id);
        const cacheTtlHours = source ? source.cacheTtlHours : 1;
        try {
            await ApiModel.updateSourceCache(id, { cacheEnabled, cacheTtlHours });
            loadSources();
        } catch (err) {
            SettingsView.showError(err.message || 'Erro ao atualizar cache do site.');
            loadSources();
        }
    });

    SettingsView.onCacheTtlChange(async (id, cacheTtlHours) => {
        SettingsView.hideError();
        const source = findSource(id);
        const cacheEnabled = source ? source.cacheEnabled : false;
        try {
            await ApiModel.updateSourceCache(id, { cacheEnabled, cacheTtlHours });
            loadSources();
        } catch (err) {
            SettingsView.showError(err.message || 'Erro ao atualizar cache do site.');
            loadSources();
        }
    });

    SettingsView.onCacheRefresh(async (id) => {
        SettingsView.hideError();
        SettingsView.setRefreshLoading(id, true);
        try {
            await ApiModel.refreshSourceCache(id);
            loadSources();
        } catch (err) {
            SettingsView.showError(err.message || 'Erro ao atualizar cache do site.');
            SettingsView.setRefreshLoading(id, false);
        }
    });

    SettingsView.onLogout(() => {
        ApiModel.logout();
    });

    loadSources();
})();
