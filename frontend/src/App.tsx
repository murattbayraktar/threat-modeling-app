import { useEffect, useState } from 'react';
import { NavLink, Route, Routes } from 'react-router-dom';
import { api, currentUser, setCurrentUser } from './api/client';
import type { Meta } from './api/types';
import { MetaContext } from './lib/useMeta';
import ReviewList from './pages/ReviewList';
import NewReview from './pages/NewReview';
import ReviewDetail from './pages/ReviewDetail';
import KnowledgeSearch from './pages/KnowledgeSearch';
import RulesView from './pages/RulesView';
import MetricsView from './pages/MetricsView';
import SystemSettingsPage from './pages/SystemSettingsPage';
import { setWebLlmConfigUrl } from './lib/webllm';

export default function App() {
  const [meta, setMeta] = useState<Meta | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [user, setUser] = useState(currentUser());

  useEffect(() => {
    const load = () =>
      api.meta().then((m) => {
        setMeta(m);
        setError(null);
        setWebLlmConfigUrl(m.llm.browserConfigUrl);
      }).catch((e) => setError(`Backend'e ulaşılamadı: ${e.message}`));
    load();
    // Sistem parametreleri kaydedilince menü ve ekranlar yeni değerleri görsün
    window.addEventListener('tm-meta-refresh', load);
    return () => window.removeEventListener('tm-meta-refresh', load);
  }, []);

  return (
    <MetaContext.Provider value={meta}>
      <div className="layout">
        <nav className="side" aria-label="Ana menü">
          <div className="brand">
            <span className="brand-mark" aria-hidden="true">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z" />
                <path d="M9 12l2 2 4-4" />
              </svg>
            </span>
            <span className="brand-text">
              <strong>TM Asistan</strong>
              <span>Threat modeling</span>
            </span>
          </div>
          <div className="nav-group">İnceleme</div>
          <NavItem to="/" end icon="list">İncelemeler</NavItem>
          <NavItem to="/new" icon="plus">Yeni inceleme</NavItem>
          <div className="nav-group">Bilgi ve kurallar</div>
          <NavItem to="/knowledge" icon="book">Bilgi tabanı (RAG)</NavItem>
          <NavItem to="/rules" icon="rules">Kurallar ve risk</NavItem>
          <NavItem to="/metrics" icon="chart">İzleme metrikleri</NavItem>
          <div className="nav-group">Yönetim</div>
          <NavItem to="/settings" icon="settings">Sistem parametreleri</NavItem>
          <div className="side-foot">
            {meta && (
              <>
                <div className="kv"><span>Sunucu LLM</span><b>{meta.llm.serverProvider}</b></div>
                <div className="kv"><span>Model</span><b>{meta.llm.serverModel}</b></div>
                <div className="kv"><span>Arama</span><b>{meta.retrievalMode}</b></div>
              </>
            )}
            <label style={{ display: 'block', marginTop: 10 }}>
              Kullanıcı
              <input
                value={user}
                onChange={(e) => {
                  setUser(e.target.value);
                  setCurrentUser(e.target.value);
                }}
                aria-label="Kullanıcı adı"
              />
            </label>
          </div>
        </nav>
        <main className="main">
          {error && <div className="notice error">{error}</div>}
          <Routes>
            <Route path="/" element={<ReviewList />} />
            <Route path="/new" element={<NewReview />} />
            <Route path="/reviews/:id" element={<ReviewDetail />} />
            <Route path="/knowledge" element={<KnowledgeSearch />} />
            <Route path="/rules" element={<RulesView />} />
            <Route path="/metrics" element={<MetricsView />} />
            <Route path="/settings" element={<SystemSettingsPage />} />
          </Routes>
        </main>
      </div>
    </MetaContext.Provider>
  );
}

const ICONS: Record<string, JSX.Element> = {
  list: <><path d="M8 6h12M8 12h12M8 18h12" /><path d="M4 6h.01M4 12h.01M4 18h.01" /></>,
  plus: <><rect x="4" y="4" width="16" height="16" rx="3" /><path d="M12 8v8M8 12h8" /></>,
  book: <><path d="M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2z" /><path d="M4 19V5" /><path d="M9 7h6" /></>,
  rules: <><path d="M5 4h14v16H5z" /><path d="M9 9l2 2 4-4M9 15h6" /></>,
  chart: <><path d="M4 20V10M10 20V4M16 20v-7M22 20H2" /></>,
  settings: <><path d="M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h0" /><circle cx="16" cy="6" r="2" /><circle cx="10" cy="12" r="2" /><circle cx="18" cy="18" r="2" /></>,
};

function NavItem({ to, end, icon, children }: { to: string; end?: boolean; icon: string; children: React.ReactNode }) {
  return (
    <NavLink to={to} end={end} className={({ isActive }) => 'nav' + (isActive ? ' active' : '')}>
      <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        {ICONS[icon]}
      </svg>
      {children}
    </NavLink>
  );
}
