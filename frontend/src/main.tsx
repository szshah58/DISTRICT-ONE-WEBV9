import React, { useEffect, useRef, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { Archive, BarChart3, Building2, CalendarDays, CreditCard, Download, FileText, Headphones, LayoutDashboard, LogOut, Menu, Newspaper, Plus, RefreshCw, Search, Settings, ShieldCheck, Smartphone, Upload, Users, WalletCards, Landmark, Banknote, SlidersHorizontal, ReceiptText, CalendarClock, Percent, ClipboardList, MapPin, Layers3, Pencil, Eye, EyeOff, ArrowLeft, X, type LucideIcon } from 'lucide-react';
import { Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, ComposedChart, Line, Pie, PieChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import districtOneLogo from './assets/district_one_logo.jpg';
import './styles.css';

const API = import.meta.env.VITE_API_URL || '/api/v1';
type User = { username: string; fullName: string; role: string; superAdmin?: boolean; modules?: string[]; permissionActions?: string[] };
type Customer = { id: number; mbrNo: string; regNo?: string; formNo?: string; name: string; cnicNo?: string; contact1?: string; city?: string; status: string };
type SeriesPoint = { month: string; label: string };
type CollectionPoint = SeriesPoint & { amount: number; receipts: number; cumulative: number };
type DemandPoint = SeriesPoint & { scheduled: number; collected: number; variance: number };
/** Recovery percentage series: recovered divided by outstanding for the same month. */
type RecoveryPercentagePoint = SeriesPoint & { billed: number; recovered: number; outstanding: number; lineCount: number; recoveryPercent: number | null };
type CountPoint = SeriesPoint & { count: number };
type AmountBucket = { label: string; count: number; amount: number };
type CountBucket = { label: string; count: number };
/** Accepts breakdowns that carry a value, a count, or both. */
type MixedBucket = { label: string; count: number; amount?: number };
type InventoryRow = { label: string; total: number; available: number; allocated: number };
type ActivityRow = { occurredAt: string; action: string; module: string };
type DashboardData = {
  members: number;
  plots: number;
  availablePlots: number;
  soldBookedPlots: number;
  collections: number;
  receivables: number;
  pendingFinanceVerification: number;
  todayCollections: number;
  monthCollections: number;
  previousMonthCollections: number;
  overdueReceivables: number;
  overdueReceivableCount: number;
  collectionRate: number;
  surcharges: number;
  averageReceipt: number;
  openCareCases: number;
  trendMonths: number;
  receivablesAging: AmountBucket[];
  receivableStatusMix: AmountBucket[];
  paymentModes: AmountBucket[];
  chargeRecovery: AmountBucket[];
  monthlyCollections: CollectionPoint[];
  monthlyDemand: DemandPoint[];
  monthlyRecoveryPercentage: RecoveryPercentagePoint[];
  monthlySalesBookings: CountPoint[];
  monthlyMembers: CountPoint[];
  projectInventory: InventoryRow[];
  blockOccupancy: InventoryRow[];
  plotStatuses: CountBucket[];
  financeStatuses: AmountBucket[];
  bookingPipeline: CountBucket[];
  carePipeline: CountBucket[];
  activityByModule: CountBucket[];
  recentActivity: ActivityRow[];
};
type AdminRoleOption = { code: string; name: string; permissions: string[] };
type AdminPermissionOption = { code: string; module: string; action: string };
type AdminPositionOption = { id: number; code: string; name: string; department?: string | null; active: boolean; permissions: string[] };
type AdminProjectOption = { id: number; projectName: string; location?: string | null; city?: string | null };
type AdminProjectScope = { projectId: number; location?: string | null; city?: string | null };
type AdminUserOptions = { roles: AdminRoleOption[]; permissions: AdminPermissionOption[]; positions: AdminPositionOption[]; projects: AdminProjectOption[] };
type AdminUserRecord = { id: number; username: string; displayName: string; email: string; mobile?: string | null; contact?: string | null; department?: string | null; role: string; active: boolean; lastLogin?: string | null; positionId?: number | null; positionCode?: string | null; accessStartsAt?: string | null; accessEndsAt?: string | null; projectScopes?: AdminProjectScope[]; permissions?: string[] };
type CustomerImportRecord = {
  classification: string; sourceFiles: string; sourceRows: string; mbrNo?: string; regNo?: string;
  customerName?: string; cnic?: string; contactInformation?: string; plotUnitInformation?: string;
  block?: string; category?: string; type?: string; instalmentInformation?: string;
  developmentCharges?: string; databaseComparison: string;
};
type CustomerImportPreview = {
  filesReviewed: number; sourceRowsReviewed: number; recordGroups: number;
  totals: { existingMatch: number; newRecords: number; duplicates: number; mismatches: number; missingRequiredInformation: number; cannotBeMapped: number };
  records: CustomerImportRecord[]; databaseChangesMade: boolean;
};

async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = sessionStorage.getItem('webv9_token');
  const publicLoginRequest = path === '/auth/login';
  const response = await fetch(`${API}${path}`, { ...options, credentials: 'include', headers: { 'Content-Type': 'application/json', ...(token && !publicLoginRequest ? { Authorization: `Bearer ${token}` } : {}), ...options.headers } });
  if (response.status === 401 && !publicLoginRequest) {
    sessionStorage.clear();
    window.location.reload();
    throw new Error('Your session has expired. Please sign in again.');
  }
  if (!response.ok) throw new Error((await response.json().catch(() => null))?.message || 'The server could not complete this request.');
  return response.json();
}

function currentSessionUser(): User | null {
  try {
    return JSON.parse(sessionStorage.getItem('webv9_user') ?? 'null') as User | null;
  } catch {
    return null;
  }
}

function canPerformAction(module: string, action: string): boolean {
  const user = currentSessionUser();
  if (user?.superAdmin) return true;
  const permissions = user?.permissionActions ?? [];
  return permissions.includes(`${module}|${action}`)
    || (module === 'Finance' && action === 'RECEIPT_CREATE' && permissions.includes('Finance|ADD'));
}

function isSessionSuperAdmin(): boolean { return Boolean(currentSessionUser()?.superAdmin); }

function Login({ onLogin }: { onLogin: (user: User, token?: string) => void }) {
  const [username, setUsername] = useState(''); const [password, setPassword] = useState(''); const [showPassword, setShowPassword] = useState(false); const [error, setError] = useState(''); const [busy, setBusy] = useState(false); const [forgotOpen, setForgotOpen] = useState(false);
  async function submit(e: React.FormEvent) { e.preventDefault(); setError(''); setBusy(true); try { const result = await api<{ token?: string; user: User }>('/auth/login', { method: 'POST', body: JSON.stringify({ username, password, rememberMe: false }) }); onLogin(result.user, result.token); } catch (err) { setError(err instanceof Error ? err.message : 'Login failed.'); } finally { setBusy(false); } }
  return <main className="login-page"><section className="login-card"><img src={districtOneLogo} className="login-logo" alt="District One Housing Society" /><p className="eyebrow">HOUSING MANAGEMENT SYSTEM</p><h1>LOGIN</h1><form onSubmit={submit}><label>Username<input required value={username} onChange={e => setUsername(e.target.value)} autoComplete="username" /></label><label>Password<div className="login-password-field"><input required type={showPassword ? 'text' : 'password'} value={password} onChange={e => setPassword(e.target.value)} autoComplete="current-password" /><button type="button" className="login-password-toggle" onClick={() => setShowPassword(visible => !visible)} aria-label={showPassword ? 'Hide password' : 'Show password'} aria-pressed={showPassword}>{showPassword ? <EyeOff size={18} /> : <Eye size={18} />}</button></div></label>{error && <div className="error">{error}</div>}<button className="primary full" disabled={busy}>{busy ? 'LOGGING IN…' : 'LOGIN'}</button></form><div className="login-links"><button type="button" className="link-button" onClick={() => setForgotOpen(open => !open)}>Forgot Password</button></div>{forgotOpen && <div className="login-notice"><p>Password recovery is handled by the District One administration office. Please contact the office to have your password reset.</p><button type="button" className="secondary compact" onClick={() => setForgotOpen(false)}>Close</button></div>}</section></main>;
}

const nav = [
  { label: 'Dashboard', icon: LayoutDashboard },
  { label: 'Admin & Setting', icon: Settings },
  { label: 'Projects', icon: Building2 },
  { label: 'Sales', icon: WalletCards },
  { label: 'Finance', icon: CreditCard },
  { label: 'Customer Care', icon: Headphones },
  { label: 'Reports', icon: FileText }
];

function App() {
  const [user, setUser] = useState<User | null>(null); const [page, setPage] = useState('Dashboard'); const [mobile, setMobile] = useState(false);
  const [authReady, setAuthReady] = useState(false);
  useEffect(() => {
    let active = true;
    const token = sessionStorage.getItem('webv9_token');
    fetch(`${API}/auth/me`, { credentials: 'include', headers: token ? { Authorization: `Bearer ${token}` } : {} })
      .then(async response => response.ok ? response.json() as Promise<{ user: User }> : null)
      .then(result => {
        if (!active) return;
        if (result?.user) { sessionStorage.setItem('webv9_user', JSON.stringify(result.user)); setUser(result.user); }
        else { sessionStorage.clear(); setUser(null); }
      })
      .catch(() => {
        if (!active) return;
        sessionStorage.clear();
        setUser(null);
      })
      .finally(() => {
        if (active) setAuthReady(true);
      });
    return () => { active = false; };
  }, []);
  function login(u: User, token?: string) { if (token) sessionStorage.setItem('webv9_token', token); else sessionStorage.removeItem('webv9_token'); sessionStorage.setItem('webv9_user', JSON.stringify(u)); setUser(u); setAuthReady(true); }
  async function logout() {
    const token = sessionStorage.getItem('webv9_token');
    try { await fetch(`${API}/auth/logout`, { method: 'POST', credentials: 'include', headers: token ? { Authorization: `Bearer ${token}` } : {} }); }
    catch { /* Clearing local state still ends this browser session. */ }
    finally { sessionStorage.clear(); setUser(null); }
  }
  if (!authReady) return <main className="login-page"><section className="login-card"><p className="eyebrow">DISTRICT ONE / WEBV9</p><p>Checking your authenticated access…</p></section></main>;
  if (!user) return <Login onLogin={login} />;
  const visibleNav = nav.filter(item => {
    if (user.superAdmin) return true;
    const required: Record<string, string[]> = {
      Dashboard: ['Dashboard'],
      'Admin & Setting': ['Users & Roles'],
      Projects: ['Plots & Sales'],
      Sales: ['Plots & Sales'],
      Finance: ['Finance'],
      'Customer Care': ['Customer Care'],
      Reports: ['Reports']
    };
    return required[item.label]?.some(module => user.modules?.includes(module)) ?? false;
  });
  const currentPage = visibleNav.some(item => item.label === page) ? page : visibleNav[0]?.label ?? '';
  return <div className={currentPage === 'Dashboard' ? 'app dashboard-app' : 'app'}><aside className={mobile ? 'sidebar open' : 'sidebar'}><div className="brand"><img src={districtOneLogo} alt="District One" /><button className="icon mobile-close" onClick={() => setMobile(false)}><X /></button></div><div className="workspace">DISTRICT ONE / WEBV9</div><nav>{visibleNav.map(item => { const Icon = item.icon; return <button className={currentPage === item.label ? 'nav-item active' : 'nav-item'} key={item.label} onClick={() => { setPage(item.label); setMobile(false); }}><Icon size={18} />{item.label}</button>; })}</nav><button className="nav-item logout" onClick={logout}><LogOut size={18} />Log out</button></aside><div className="content"><header><div className="header-copy"><button className="icon mobile-menu" onClick={() => setMobile(true)}><Menu /></button>{currentPage && currentPage !== 'Dashboard' && <button className="secondary header-back" onClick={() => setPage(visibleNav[0]?.label ?? '')}><ArrowLeft size={15} /> Back</button>}<div><p className="eyebrow">DISTRICT ONE / {currentPage.toUpperCase()}</p><h2>{currentPage || 'No authorized modules'}</h2></div></div><div className="profile"><button className="secondary header-refresh" onClick={() => window.location.reload()}><RefreshCw size={14} /> Refresh</button><div className="avatar">{(user.fullName || user.username).slice(0, 1).toUpperCase()}</div><div><span>{user.fullName || user.username}</span><b>{user.role.replace('_', ' ')}</b></div><button className="secondary header-logout" onClick={logout}><LogOut size={14} /> Logout</button></div></header><main className="page">{!currentPage ? <div className="empty-page"><h1>No module access is assigned</h1><p className="muted">Contact an administrator to review your job-title permissions and project scope.</p></div> : currentPage === 'Dashboard' ? <Dashboard user={user} onNavigate={setPage} /> : currentPage === 'Admin & Setting' ? <Admin user={user} /> : currentPage === 'Projects' ? <Projects /> : currentPage === 'Sales' ? <Plots /> : currentPage === 'Finance' ? <Finance /> : currentPage === 'Customer Care' ? <Care />   : currentPage === 'Reports' ? <ReportsWorkspace user={user} /> : <ModulePage title={currentPage} />}</main></div></div>;
}

function Dashboard({ user, onNavigate }: { user: User; onNavigate: (page: string) => void }) {
  const [data, setData] = useState<DashboardData | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [asOf, setAsOf] = useState('');
  const [range, setRange] = useState(12);
  const load = () => {
    setLoading(true);
    setError('');
    api<DashboardData>('/dashboard').then(result => {
      setData(result);
      setAsOf(new Date().toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' }));
    }).catch(e => setError(e.message)).finally(() => setLoading(false));
  };
  useEffect(() => { load(); }, []);

  const tail = <T,>(rows: T[]) => rows.slice(-range);
  const statusBucket = (status: string) => data?.financeStatuses.find(row => tidy(row.label).toUpperCase() === status);
  const pendingStatuses = data?.financeStatuses.filter(row => ['RECOVERY_ENTRY', 'PENDING', 'PENDING_VERIFICATION'].includes(tidy(row.label).toUpperCase())) ?? [];
  const pendingAmount = pendingStatuses.reduce((sum, row) => sum + num(row.amount), 0);
  const receiptCount = data?.financeStatuses.reduce((sum, row) => sum + num(row.count), 0) ?? 0;
  const careTotal = data?.carePipeline.reduce((sum, row) => sum + num(row.count), 0) ?? 0;
  const projectCount = data?.projectInventory.filter(row => tidy(row.label).toLowerCase() !== 'unassigned inventory').length ?? 0;
  const authorized = (module: string) => Boolean(user.superAdmin || user.modules?.includes(module));
  const quickActions: { page: string; module: string; label: string; detail: string; icon: LucideIcon }[] = [
    { page: 'Projects', module: 'Plots & Sales', label: 'Project inventory', detail: 'Projects, blocks and plots', icon: Building2 },
    { page: 'Sales', module: 'Plots & Sales', label: 'Sales & bookings', detail: 'Bookings and plot allocations', icon: WalletCards },
    { page: 'Finance', module: 'Finance', label: 'Finance workspace', detail: 'Receipts and recovery', icon: CreditCard },
    { page: 'Customer Care', module: 'Customer Care', label: 'Customer Care', detail: 'Member cases and follow-ups', icon: Headphones },
    { page: 'Reports', module: 'Reports', label: 'Reports', detail: 'Operational and financial reports', icon: BarChart3 }
  ].filter(action => authorized(action.module));
  const cards = data ? [
    { key: 'projects', title: 'Total Projects', value: projectCount.toLocaleString(), hint: 'Projects represented in inventory', icon: Building2, tone: 'plain' as const },
    { key: 'plots', title: 'Total Plots', value: num(data.plots).toLocaleString(), hint: 'Plots in the live inventory', icon: Layers3, tone: 'plain' as const },
    { key: 'sold', title: 'Sold Plots', value: num(data.soldBookedPlots).toLocaleString(), hint: 'Sold, booked or allocated plots', icon: WalletCards, tone: 'good' as const },
    { key: 'available', title: 'Available Plots', value: num(data.availablePlots).toLocaleString(), hint: 'Plots currently available', icon: Archive, tone: 'gold' as const },
    { key: 'collections', title: 'Total Collections', value: formatMoney(num(data.collections)), hint: 'Verified and posted collections', icon: Banknote, tone: 'good' as const },
    { key: 'outstanding', title: 'Outstanding Amount', value: formatMoney(num(data.receivables)), hint: 'Current recorded receivables', icon: CreditCard, tone: 'gold' as const }
  ] : [];

  return <div className="executive-dashboard">
    <div className="welcome executive-welcome">
      <div className="welcome-copy">
        <p className="eyebrow">DISTRICT ONE · EXECUTIVE OVERVIEW</p>
        <h1>Welcome back</h1>
        <p className="welcome-username">{user.fullName || user.username}</p>
        <p className="executive-welcome-note">A clear view of performance, collections and member service.</p>
      </div>
      <div className="executive-welcome-actions">
        <span className="executive-live"><i /> Live database{asOf ? ` · ${asOf}` : ''}</span>
        <button className="primary executive-refresh" onClick={load} disabled={loading}><RefreshCw size={15} /> {loading ? 'Refreshing…' : 'Refresh data'}</button>
      </div>
    </div>
    {error && <div className="error page-error">Dashboard data could not be loaded: {error}</div>}
    {loading && !data ? <div className="panel empty">Loading live dashboard data…</div> : data && <>
      <section className="executive-section" aria-label="Executive KPI summary">
        <div className="executive-section-heading">
          <div><p className="eyebrow">AT A GLANCE</p><h2>Executive snapshot</h2></div>
          <span>Six live business indicators</span>
        </div>
        <div className="executive-kpi-grid">{cards.map(card => {
          const Icon = card.icon;
          return <article className={`executive-kpi executive-kpi-${card.tone}`} key={card.key}>
            <div className="executive-kpi-top"><span>{card.title}</span><i><Icon size={18} /></i></div>
            <strong>{card.value}</strong>
            <small>{card.hint}</small>
          </article>;
        })}</div>
      </section>

      <section className="executive-section" aria-label="Financial overview">
        <VizSection title="Financial overview" note="Collections, recovery and receivables" />
        <div className="viz-grid dashboard-financial-grid">
          <VizPanel
            className="span-all executive-chart-feature"
            eyebrow="COLLECTIONS & CASH REALISATION"
            title="Monthly collections and running total"
            hint="Posted and verified receipts, with the cumulative collected balance."
            height={292}
            action={<RangeToggle value={range} onChange={setRange} />}
            legend={[
              { label: 'Collected in month', color: '#183b62' },
              { label: 'Cumulative collected', color: '#c29a4b' }
            ]}
          >
            <CollectionsChart rows={tail(data.monthlyCollections)} />
          </VizPanel>
          <VizPanel eyebrow="RECOVERY PERFORMANCE" title="Instalment demand vs collections" hint="Amount falling due against money recovered in each month." height={258} action={<RangeToggle value={range} onChange={setRange} />} legend={[{ label: 'Scheduled to fall due', color: '#d9c18d' }, { label: 'Collected in month', color: '#183b62' }]}>
            <DemandChart rows={tail(data.monthlyDemand)} />
          </VizPanel>
          <VizPanel eyebrow="RECOVERY RATE" title="Recovery against instalment demand" hint="Monthly collections compared with the amount due." height={258} action={<RangeToggle value={range} onChange={setRange} />} legend={RECOVERY_BANDS}>
            <RecoveryRateChart rows={tail(data.monthlyDemand)} />
          </VizPanel>
          <VizPanel eyebrow="RECOVERY VS OUTSTANDING" title="Recovered amount vs outstanding" hint="Monthly recovered amount compared with the amount still outstanding." height={258} action={<RangeToggle value={range} onChange={setRange} />} legend={RECOVERY_PERCENT_BANDS}>
            <RecoveryPercentageChart rows={tail(data.monthlyRecoveryPercentage)} />
          </VizPanel>
          <VizPanel eyebrow="DEBT QUALITY" title="Receivables ageing" hint="Outstanding balance by how long the due date has passed." height={244} legend={AGE_SEVERITY.map((label, index) => ({ label, color: AGE_COLORS[index] }))}>
            <RankedAmountBars rows={data.receivablesAging} colorFor={ageColor} />
          </VizPanel>
          <VizPanel eyebrow="COLLECTION CHANNELS" title="Payment modes" hint="Verified collection share by payment mode." height={244}>
            <ShareBars rows={data.paymentModes} />
          </VizPanel>
          <VizPanel eyebrow="RECOVERY BY CHARGE" title="Outstanding by charge head" hint="Recorded member balance grouped by charge head." height={244}>
            <ShareBars rows={data.chargeRecovery} />
          </VizPanel>
          <VizPanel eyebrow="RECEIVABLE STATUS" title="Outstanding status mix" hint="Outstanding balance grouped by receivable lifecycle status." height={244} legend={data.receivableStatusMix.map((row, index) => ({ label: tidy(row.label), color: VIZ_COLORS[index % VIZ_COLORS.length] }))}>
            <DonutChart rows={data.receivableStatusMix} valueKey="amount" centerLabel="Total owing" centerValue={shortMoney(data.receivables)} centerNote={`${data.receivableStatusMix.length} status groups`} />
          </VizPanel>
        </div>
      </section>

      <section className="executive-section" aria-label="Finance receipt workflow">
        <VizSection title="Finance workflow" note="Live receipt counts and value by status" />
        <div className="panel finance-workflow-panel">
          <div className="finance-workflow-heading">
            <div><p className="eyebrow">RECEIPT OPERATIONS</p><h3>Finance processing pipeline</h3><p>Figures are drawn from existing Dashboard receipt status data.</p></div>
            {authorized('Finance') && <button className="secondary workflow-link" onClick={() => onNavigate('Finance')}><ReceiptText size={15} /> Open Finance</button>}
          </div>
          <div className="finance-status-grid">
            {[
              { label: 'Recovery Entry', count: num(statusBucket('RECOVERY_ENTRY')?.count), amount: num(statusBucket('RECOVERY_ENTRY')?.amount), icon: FileText, tone: 'gold' },
              { label: 'Pending Verification', count: num(data.pendingFinanceVerification), amount: pendingAmount, icon: CalendarClock, tone: 'pending' },
              { label: 'Verified', count: num(statusBucket('VERIFIED')?.count), amount: num(statusBucket('VERIFIED')?.amount), icon: ShieldCheck, tone: 'verified' },
              { label: 'Posted Receipts', count: num(statusBucket('POSTED')?.count), amount: num(statusBucket('POSTED')?.amount), icon: ReceiptText, tone: 'posted' }
            ].map(item => {
              const Icon = item.icon;
              return <article className={`finance-status-card finance-status-${item.tone}`} key={item.label}>
                <span className="finance-status-icon"><Icon size={17} /></span><span className="finance-status-label">{item.label}</span>
                <strong>{item.count.toLocaleString()}</strong><small>{formatMoney(item.amount)}</small>
              </article>;
            })}
          </div>
          <div className="finance-workflow-foot"><span>{receiptCount.toLocaleString()} receipts across all recorded statuses</span><span>Pending verification includes recovery entries awaiting review.</span></div>
        </div>
        <div className="viz-grid dashboard-finance-detail">
          <VizPanel eyebrow="RECEIPT STATUS MIX" title="Receipt pipeline value" hint="Receipt count and value by every recorded workflow status." height={238}>
            <RankedAmountBars rows={data.financeStatuses} colorFor={receiptColor} />
          </VizPanel>
          <VizPanel eyebrow="FINANCE COLLECTIONS" title="Collection trend context" hint="Verified collection channels and payment methods." height={238}>
            <ShareBars rows={data.paymentModes} />
          </VizPanel>
        </div>
      </section>

      <section className="executive-section" aria-label="Project performance and inventory">
        <VizSection title="Project performance" note="Project inventory, sold plots and availability" />
        <div className="project-performance-grid">
          <section className="panel executive-project-panel">
            <div className="panel-head project-performance-head">
              <div><p className="eyebrow">PROJECT INVENTORY</p><h3>Inventory by project</h3><p className="project-scope-note">Projects represented in the live plot inventory.</p></div>
              {authorized('Plots & Sales') && <button className="secondary workflow-link" onClick={() => onNavigate('Projects')}><Building2 size={15} /> Open Projects</button>}
            </div>
            <div className="project-performance-table-wrap">
              {data.projectInventory.length ? <table className="project-performance-table">
                <thead><tr><th>Project</th><th>Total plots</th><th>Sold / allocated</th><th>Available</th></tr></thead>
                <tbody>{data.projectInventory.map((row, index) => <tr key={`${row.label}-${index}`}>
                  <td><strong>{row.label}</strong></td><td>{num(row.total).toLocaleString()}</td>
                  <td>{num(row.allocated).toLocaleString()}</td><td>{num(row.available).toLocaleString()}</td>
                </tr>)}</tbody>
              </table> : <div className="chart-empty">No project inventory is available.</div>}
            </div>
            <div className="project-performance-chart"><InventoryStack rows={data.projectInventory} /></div>
          </section>
          <VizPanel eyebrow="PLOT STATUS" title="Overall inventory status" hint="Live plot count grouped by status." height={300} legend={data.plotStatuses.map((row, index) => ({ label: tidy(row.label), color: VIZ_COLORS[index % VIZ_COLORS.length] }))}>
            <DonutChart rows={data.plotStatuses} valueKey="count" centerLabel="Total plots" centerValue={num(data.plots).toLocaleString()} centerNote={`${num(data.availablePlots).toLocaleString()} available`} />
          </VizPanel>
        </div>
        <div className="viz-grid dashboard-operations-grid">
          <VizPanel eyebrow="BLOCK OCCUPANCY" title="Inventory by block" hint="Ten largest blocks split by allocated and available plots." height={252} legend={[{ label: 'Sold / allocated', color: '#183b62' }, { label: 'Available', color: '#d9c18d' }]}>
            <BlockOccupancyChart rows={data.blockOccupancy} />
          </VizPanel>
          <VizPanel eyebrow="SALES PIPELINE" title="Bookings by month" hint="New plot allocations created each month." height={252} action={<RangeToggle value={range} onChange={setRange} />}>
            <BookingsChart rows={tail(data.monthlySalesBookings)} />
          </VizPanel>
          <VizPanel eyebrow="BOOKING LIFECYCLE" title="Booking pipeline" hint="Live sales bookings grouped by their current state." height={252}>
            <ShareBars rows={data.bookingPipeline} kind="count" />
          </VizPanel>
          <VizPanel eyebrow="MEMBER INTAKE" title="New member registrations" hint="Members added to the register each month." height={252} action={<RangeToggle value={range} onChange={setRange} />}>
            <MembersChart rows={tail(data.monthlyMembers)} />
          </VizPanel>
        </div>
      </section>

      <section className="executive-section" aria-label="Customer Care summary">
        <VizSection title="Customer Care" note="Member service workload and case status" />
        <div className="care-overview-grid">
          <div className="panel care-summary-panel">
            <div className="care-summary-heading"><span className="care-summary-icon"><Headphones size={19} /></span><div><p className="eyebrow">OPEN CASE BACKLOG</p><h3>Member service</h3></div></div>
            <strong className="care-open-count">{num(data.openCareCases).toLocaleString()}</strong><span className="care-open-label">open cases</span>
            <div className="care-summary-foot"><span>{careTotal.toLocaleString()} cases across all statuses</span><span>{data.carePipeline.length} status groups</span></div>
            {authorized('Customer Care') && <button className="secondary workflow-link" onClick={() => onNavigate('Customer Care')}><Headphones size={15} /> Open Customer Care</button>}
          </div>
          <VizPanel eyebrow="CASE STATUS" title="Customer Care workload" hint="Current cases grouped by workflow status." height={258}>
            <ShareBars rows={data.carePipeline} kind="count" />
          </VizPanel>
        </div>
      </section>

      <section className="executive-section" aria-label="Recent activity">
        <VizSection title="Recent activity" note="Latest audit events and activity by module" />
        <div className="activity-actions-grid">
          <div className="panel dashboard-activity executive-activity">
            <div className="panel-head"><div><p className="eyebrow">SYSTEM LOG</p><h3>Recent activity</h3></div><span className="activity-total">{data.recentActivity.length} latest events</span></div>
            {data.recentActivity.length ? <div className="activity-list">{data.recentActivity.map((item, index) => <div className="activity-row" key={`${item.occurredAt}-${index}`}><span className="activity-mark" /><div><strong>{item.action || 'Activity'} <small>· {item.module || 'System'}</small></strong><span>{item.occurredAt}</span></div></div>)}</div> : <div className="chart-empty">No recent activity has been recorded.</div>}
          </div>
          <VizPanel eyebrow="ACTIVITY DISTRIBUTION" title="Activity by module" hint="Audit events recorded across modules in the last 30 days." height={268}>
            <RankedAmountBars rows={data.activityByModule} kind="count" colorFor={() => '#c29a4b'} />
          </VizPanel>
        </div>
      </section>

      {quickActions.length > 0 && <section className="executive-section" aria-label="Quick actions">
        <div className="executive-section-heading">
          <div><p className="eyebrow">WORKSPACE SHORTCUTS</p><h2>Quick actions</h2></div>
          <span>Shortcuts reflect your authorized modules</span>
        </div>
        <div className="executive-quick-grid">{quickActions.map(action => {
          const Icon = action.icon;
          return <button className="executive-quick-action" key={action.page} onClick={() => onNavigate(action.page)}>
            <span><Icon size={18} /></span><strong>{action.label}</strong><small>{action.detail}</small><ArrowLeft size={15} className="executive-action-arrow" />
          </button>;
        })}</div>
      </section>}
    </>}
  </div>;
}

/* ---------------- Dashboard visualisation layer ---------------- */

const VIZ_COLORS = ['#183b62', '#c29a4b', '#315a7d', '#ddc78f', '#52799a', '#9d7b3d', '#8198a8', '#b39b6a'];
const AGE_SEVERITY = ['Not yet due', '1-30 days', '31-60 days', '61-90 days', 'Over 90 days'];
const AGE_COLORS = ['#7fb069', '#c9a227', '#e08a3c', '#d16b65', '#a8413c'];
const RECEIPT_TONES: Record<string, string> = {
  POSTED: '#183b62', VERIFIED: '#52799a',
  PENDING: '#c29a4b', PENDING_VERIFICATION: '#c29a4b', RECOVERY_ENTRY: '#9d7b3d',
  REJECTED: '#c05c56', REVERSED: '#a8413c', CANCELLED: '#8aa0ad'
};
const VIZ_GRID = '#edf3f5';
const axisTick = { fontSize: 11, fill: '#7b95a4' };

const num = (value: unknown) => {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
};
const tidy = (value: string) => (value || 'Unknown').trim().replace(/_/g, ' ');
/** Compact magnitude for axis ticks and dense tiles, e.g. 1.2M / 450K. */
function compact(value: unknown) {
  const amount = num(value);
  const sign = amount < 0 ? '-' : '';
  const abs = Math.abs(amount);
  if (abs >= 1e9) return `${sign}${(abs / 1e9).toFixed(abs >= 1e10 ? 0 : 1)}B`;
  if (abs >= 1e6) return `${sign}${(abs / 1e6).toFixed(abs >= 1e7 ? 0 : 1)}M`;
  if (abs >= 1e3) return `${sign}${(abs / 1e3).toFixed(abs >= 1e4 ? 0 : 1)}K`;
  return `${sign}${Math.round(abs)}`;
}
const shortMoney = (value: unknown) => `Rs ${compact(value)}`;
const axisMoney = (value: number) => compact(value);
const share = (part: number, whole: number) => (whole > 0 ? Math.max(0, Math.min(100, (part / whole) * 100)) : 0);
const truncate = (value: string, max: number) => (value.length > max ? `${value.slice(0, max - 1)}…` : value);

function ageColor(label: string) {
  const index = AGE_SEVERITY.indexOf(tidy(label));
  return AGE_COLORS[index < 0 ? AGE_COLORS.length - 1 : index];
}
function receiptColor(label: string, index: number) {
  return RECEIPT_TONES[tidy(label).toUpperCase()] || VIZ_COLORS[index % VIZ_COLORS.length];
}

/** Instalment demand counts as fully recovered at 100%; the bands colour the recovery graphic. */
const RECOVERY_TARGET = 100;
const RECOVERY_BANDS: VizLegendItem[] = [
  { label: '100% or better', color: '#2e9e6b' },
  { label: '80-100%', color: '#52799a' },
  { label: '60-80%', color: '#c29a4b' },
  { label: 'Under 60%', color: '#c8564f' }
];
const axisPercent = (value: number) => `${value}%`;

/** Null means nothing fell due that month, so an idle period is never read as a total miss. */
function recoveryColor(recovery: number | null) {
  if (recovery === null) return '#dbe6ea';
  if (recovery >= RECOVERY_TARGET) return RECOVERY_BANDS[0].color;
  if (recovery >= 80) return RECOVERY_BANDS[1].color;
  if (recovery >= 60) return RECOVERY_BANDS[2].color;
  return RECOVERY_BANDS[3].color;
}

/**
 * Recovered-against-outstanding recovery, where 100% means a month recovered exactly the
 * balance it still owed. Bands are therefore set around parity rather than around a demand
 * target, because this ratio clears 100% whenever a month also clears older arrears.
 */
const RECOVERY_PERCENT_PARITY = 100;
const RECOVERY_PERCENT_BANDS: VizLegendItem[] = [
  { label: '100% and above', color: '#2e9e6b' },
  { label: '75% to 100%', color: '#52799a' },
  { label: '50% to 75%', color: '#c29a4b' },
  { label: 'Under 50%', color: '#c8564f' }
];
const RECOVERY_PERCENT_NONE = '#dbe6ea';

/** Null means nothing was outstanding that month, so no ratio exists to colour. */
function recoveryPercentColor(recovery: number | null) {
  if (recovery === null) return RECOVERY_PERCENT_NONE;
  if (recovery >= RECOVERY_PERCENT_PARITY) return RECOVERY_PERCENT_BANDS[0].color;
  if (recovery >= 75) return RECOVERY_PERCENT_BANDS[1].color;
  if (recovery >= 50) return RECOVERY_PERCENT_BANDS[2].color;
  return RECOVERY_PERCENT_BANDS[3].color;
}

function VizSection({ title, note }: { title: string; note: string }) {
  return <div className="viz-section">
    <h3>{title}</h3>
    <span>{note}</span>
  </div>;
}

type VizLegendItem = { label: string; color: string };

function VizPanel({ eyebrow, title, hint, legend, height = 244, action, className = '', children }: {
  eyebrow: string; title: string; hint?: string; legend?: VizLegendItem[]; height?: number;
  action?: React.ReactNode; className?: string; children: React.ReactNode;
}) {
  return <div className={`panel viz-panel ${className}`}>
    <div className="panel-head viz-head">
      <div>
        <p className="eyebrow">{eyebrow}</p>
        <h3>{title}</h3>
        {hint && <p className="viz-hint">{hint}</p>}
      </div>
      {action}
    </div>
    {legend && legend.length > 0 && <ul className="viz-legend">{legend.map(item => <li key={item.label}><span className="viz-swatch" style={{ background: item.color }} />{item.label}</li>)}</ul>}
    <div className="viz-canvas" style={{ height }}>{children}</div>
  </div>;
}

function RangeToggle({ value, onChange }: { value: number; onChange: (value: number) => void }) {
  return <div className="viz-range">
    {[6, 12].map(months => <button key={months} type="button" className={value === months ? 'active' : ''} onClick={() => onChange(months)}>{months}M</button>)}
  </div>;
}

type VizTipEntry = { name?: string; value?: number | string; color?: string; dataKey?: string | number };
type VizTipProps = { active?: boolean; payload?: VizTipEntry[]; label?: string | number; kind?: 'money' | 'count' };

function VizTooltip({ active, payload, label, kind = 'money' }: VizTipProps) {
  if (!active || !payload || payload.length === 0) return null;
  return <div className="viz-tip">
    {label !== undefined && <p className="viz-tip-title">{label}</p>}
    <ul>{payload.filter(entry => entry.value !== undefined).map((entry, index) => <li key={`${entry.dataKey ?? entry.name}-${index}`}>
      <span className="viz-swatch" style={{ background: entry.color }} />
      <span className="viz-tip-name">{entry.name}</span>
      <b>{kind === 'money' ? formatMoney(num(entry.value)) : num(entry.value).toLocaleString()}</b>
    </li>)}</ul>
  </div>;
}

function ChartEmpty({ label = 'No data recorded for this period yet.' }: { label?: string }) {
  return <div className="chart-empty">{label}</div>;
}

/** Horizontal magnitude bars, one row per category, coloured by a caller-supplied tone. */
function RankedAmountBars({ rows, kind = 'money', colorFor }: {
  rows: MixedBucket[]; kind?: 'money' | 'count'; colorFor?: (label: string, index: number) => string;
}) {
  const data = rows.map(row => ({ label: tidy(row.label), amount: num(row.amount), count: num(row.count) }));
  if (data.length === 0) return <ChartEmpty />;
  // Category axes read bottom-up, so reversing keeps the first bucket at the top.
  return <div className="viz-canvas-body">
    <ResponsiveContainer width="100%" height="100%">
      <BarChart data={[...data].reverse()} layout="vertical" margin={{ top: 4, right: 22, bottom: 2, left: 4 }} barCategoryGap={9}>
        <CartesianGrid stroke={VIZ_GRID} horizontal={false} />
        <XAxis type="number" tick={axisTick} axisLine={false} tickLine={false} tickFormatter={kind === 'money' ? axisMoney : value => compact(value)} allowDecimals={kind === 'count'} />
        <YAxis type="category" dataKey="label" tick={axisTick} axisLine={false} tickLine={false} width={96} tickFormatter={value => truncate(String(value), 15)} />
        <Tooltip content={<VizTooltip kind={kind} />} cursor={{ fill: '#f3f8fa' }} />
        <Bar dataKey={kind === 'money' ? 'amount' : 'count'} name={kind === 'money' ? 'Value' : 'Records'} radius={[0, 5, 5, 0]} maxBarSize={22}>
          {[...data].reverse().map((row, index) => <Cell key={row.label} fill={colorFor ? colorFor(row.label, index) : VIZ_COLORS[index % VIZ_COLORS.length]} />)}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  </div>;
}

/** Proportion bars with value and share, used where a composition reads better than a chart. */
function ShareBars({ rows, kind = 'money' }: { rows: MixedBucket[]; kind?: 'money' | 'count' }) {
  const data = rows.map(row => ({ label: tidy(row.label), amount: num(row.amount), count: num(row.count) }))
    .filter(row => (kind === 'money' ? row.amount : row.count) > 0)
    .sort((a, b) => (kind === 'money' ? b.amount - a.amount : b.count - a.count));
  const total = data.reduce((sum, row) => sum + (kind === 'money' ? row.amount : row.count), 0);
  if (data.length === 0 || total <= 0) return <ChartEmpty />;
  return <ul className="viz-share">
    {data.map((row, index) => {
      const value = kind === 'money' ? row.amount : row.count;
      const percent = (value / total) * 100;
      return <li key={row.label}>
        <span className="viz-share-name" title={row.label}>{row.label}</span>
        <span className="viz-share-track"><i style={{ width: `${percent}%`, background: VIZ_COLORS[index % VIZ_COLORS.length] }} /></span>
        <b>{kind === 'money' ? shortMoney(row.amount) : row.count.toLocaleString()}</b>
        <em>{percent.toFixed(1)}%</em>
      </li>;
    })}
  </ul>;
}

/** Donut with the headline total set into the middle of the ring. */
function DonutChart({ rows, valueKey, centerLabel, centerValue, centerNote }: {
  rows: MixedBucket[]; valueKey: string;
  centerLabel: string; centerValue: string; centerNote?: string;
}) {
  const data = rows.map(row => ({ ...row, label: tidy(row.label) }));
  if (data.length === 0) return <ChartEmpty />;
  const kind = valueKey === 'amount' ? 'money' : 'count';
  return <div className="viz-donut">
    <ResponsiveContainer width="100%" height="100%">
      <PieChart>
        <Pie data={data} dataKey={valueKey} nameKey="label" innerRadius="60%" outerRadius="92%" paddingAngle={1.5} stroke="#fff" strokeWidth={2} startAngle={90} endAngle={-270} isAnimationActive={false}>
          {data.map((row, index) => <Cell key={row.label} fill={VIZ_COLORS[index % VIZ_COLORS.length]} />)}
        </Pie>
        <Tooltip content={<VizTooltip kind={kind} />} />
      </PieChart>
    </ResponsiveContainer>
    <div className="viz-donut-center">
      <span>{centerLabel}</span>
      <strong>{centerValue}</strong>
      {centerNote && <small>{centerNote}</small>}
    </div>
  </div>;
}

function DemandChart({ rows }: { rows: DemandPoint[] }) {
  const data = rows.map(row => ({ ...row, scheduled: num(row.scheduled), collected: num(row.collected) }));
  if (data.length === 0) return <ChartEmpty />;
  return <ResponsiveContainer width="100%" height="100%">
    <ComposedChart data={data} margin={{ top: 14, right: 18, bottom: 2, left: 2 }}>
      <CartesianGrid stroke={VIZ_GRID} vertical={false} />
      <XAxis dataKey="label" tick={axisTick} axisLine={false} tickLine={false} minTickGap={8} />
      <YAxis tick={axisTick} axisLine={false} tickLine={false} width={50} tickFormatter={axisMoney} />
      <Tooltip content={<VizTooltip kind="money" />} cursor={{ fill: '#f3f8fa' }} />
      <ReferenceLine y={0} stroke="#d8e6eb" />
      <Bar dataKey="scheduled" name="Scheduled to fall due" fill="#cfe3ea" radius={[4, 4, 0, 0]} maxBarSize={30} isAnimationActive={false} />
      <Line type="monotone" dataKey="collected" name="Collected in month" stroke="#183b62" strokeWidth={2.5} dot={{ r: 3, fill: '#183b62', stroke: '#fff', strokeWidth: 1.5 }} activeDot={{ r: 5 }} isAnimationActive={false} />
    </ComposedChart>
  </ResponsiveContainer>;
}

type RecoveryRow = { label: string; month: string; recovery: number | null; scheduled: number; collected: number };

function RecoveryTip({ active, payload }: { active?: boolean; payload?: Array<{ payload?: RecoveryRow }> }) {
  if (!active || !payload || payload.length === 0) return null;
  const row = payload[0]?.payload;
  if (!row) return null;
  return <div className="viz-tip">
    <p className="viz-tip-title">{row.label}</p>
    <ul>
      <li><span className="viz-swatch" style={{ background: recoveryColor(row.recovery) }} /><span className="viz-tip-name">Recovery</span><b>{row.recovery === null ? 'No demand' : `${row.recovery.toFixed(1)}%`}</b></li>
      <li><span className="viz-swatch" style={{ background: '#cfe3ea' }} /><span className="viz-tip-name">Collected</span><b>{formatMoney(num(row.collected))}</b></li>
      <li><span className="viz-swatch" style={{ background: '#e8eff2' }} /><span className="viz-tip-name">Fell due</span><b>{formatMoney(num(row.scheduled))}</b></li>
    </ul>
  </div>;
}

/** Recovery percentage per month, banded against the 100% target line. */
function RecoveryRateChart({ rows }: { rows: DemandPoint[] }) {
  const data: RecoveryRow[] = rows.map(row => {
    const scheduled = num(row.scheduled);
    return {
      label: row.label,
      month: row.month,
      scheduled,
      collected: num(row.collected),
      recovery: scheduled > 0 ? (num(row.collected) / scheduled) * 100 : null
    };
  });
  if (data.every(row => row.recovery === null)) return <ChartEmpty label="No instalment demand has fallen due in this period, so recovery cannot be measured yet." />;
  return <ResponsiveContainer width="100%" height="100%">
    <ComposedChart data={data} margin={{ top: 14, right: 18, bottom: 2, left: 2 }}>
      <CartesianGrid stroke={VIZ_GRID} vertical={false} />
      <XAxis dataKey="label" tick={axisTick} axisLine={false} tickLine={false} minTickGap={8} />
      <YAxis tick={axisTick} axisLine={false} tickLine={false} width={46} tickFormatter={axisPercent} domain={[0, 'auto']} allowDecimals={false} />
      <Tooltip content={<RecoveryTip />} cursor={{ fill: '#f3f8fa' }} />
      <ReferenceLine y={RECOVERY_TARGET} stroke="#c29a4b" strokeDasharray="5 3" label={{ value: 'Target 100%', position: 'insideTopRight', fill: '#7b95a4', fontSize: 10 }} />
      <Bar dataKey="recovery" name="Recovery" radius={[4, 4, 0, 0]} maxBarSize={38} isAnimationActive={false}>
        {data.map(row => <Cell key={row.month} fill={recoveryColor(row.recovery)} />)}
      </Bar>
    </ComposedChart>
  </ResponsiveContainer>;
}

type RecoveryPercentRow = RecoveryPercentagePoint & { recoveryPercent: number | null };

function RecoveryPercentTip({ active, payload }: { active?: boolean; payload?: Array<{ payload?: RecoveryPercentRow }> }) {
  if (!active || !payload || payload.length === 0) return null;
  const row = payload[0]?.payload;
  if (!row) return null;
  const percent = row.recoveryPercent;
  return <div className="viz-tip">
    <p className="viz-tip-title">{row.label}</p>
    <ul>
      <li><span className="viz-swatch" style={{ background: recoveryPercentColor(percent) }} /><span className="viz-tip-name">Recovery</span><b>{percent === null ? 'Nothing outstanding' : `${percent.toFixed(1)}%`}</b></li>
      <li><span className="viz-swatch" style={{ background: '#0d8294' }} /><span className="viz-tip-name">Recovered</span><b>{formatMoney(num(row.recovered))}</b></li>
      <li><span className="viz-swatch" style={{ background: '#c8564f' }} /><span className="viz-tip-name">Outstanding</span><b>{formatMoney(num(row.outstanding))}</b></li>
      <li><span className="viz-swatch" style={{ background: '#cfe3ea' }} /><span className="viz-tip-name">Billed</span><b>{formatMoney(num(row.billed))}</b></li>
    </ul>
  </div>;
}

/** Monthly recovery percentage: real recovered amount over real outstanding amount, times 100. */
function RecoveryPercentageChart({ rows }: { rows: RecoveryPercentagePoint[] }) {
  const data: RecoveryPercentRow[] = rows.map(row => ({ ...row, recoveryPercent: row.recoveryPercent === null ? null : num(row.recoveryPercent) }));
  if (data.every(row => row.recoveryPercent === null)) return <ChartEmpty label="No outstanding balance is recorded against these months, so recovery percentage cannot be measured yet." />;
  return <ResponsiveContainer width="100%" height="100%">
    <ComposedChart data={data} margin={{ top: 14, right: 18, bottom: 2, left: 2 }}>
      <CartesianGrid stroke={VIZ_GRID} vertical={false} />
      <XAxis dataKey="label" tick={axisTick} axisLine={false} tickLine={false} minTickGap={8} />
      <YAxis tick={axisTick} axisLine={false} tickLine={false} width={46} tickFormatter={axisPercent} domain={[0, 'auto']} allowDecimals={false} />
      <Tooltip content={<RecoveryPercentTip />} cursor={{ fill: '#f3f8fa' }} />
      <ReferenceLine y={RECOVERY_PERCENT_PARITY} stroke="#c29a4b" strokeDasharray="5 3" label={{ value: 'Parity 100%', position: 'insideTopRight', fill: '#7b95a4', fontSize: 10 }} />
      <Bar dataKey="recoveryPercent" name="Recovery percentage" radius={[4, 4, 0, 0]} maxBarSize={38} isAnimationActive={false}>
        {data.map(row => <Cell key={row.month} fill={recoveryPercentColor(row.recoveryPercent)} />)}
      </Bar>
    </ComposedChart>
  </ResponsiveContainer>;
}

function CollectionsChart({ rows }: { rows: CollectionPoint[] }) {
  const data = rows.map(row => ({ ...row, amount: num(row.amount), cumulative: num(row.cumulative) }));
  if (data.length === 0) return <ChartEmpty />;
  return <ResponsiveContainer width="100%" height="100%">
    <ComposedChart data={data} margin={{ top: 14, right: 18, bottom: 2, left: 2 }}>
      <defs>
        <linearGradient id="webv9CollectionFill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#183b62" stopOpacity={0.32} />
          <stop offset="100%" stopColor="#183b62" stopOpacity={0.02} />
        </linearGradient>
      </defs>
      <CartesianGrid stroke={VIZ_GRID} vertical={false} />
      <XAxis dataKey="label" tick={axisTick} axisLine={false} tickLine={false} minTickGap={8} />
      <YAxis yAxisId="month" tick={axisTick} axisLine={false} tickLine={false} width={50} tickFormatter={axisMoney} />
      <YAxis yAxisId="cumulative" orientation="right" tick={axisTick} axisLine={false} tickLine={false} width={50} tickFormatter={axisMoney} />
      <Tooltip content={<VizTooltip kind="money" />} cursor={{ stroke: '#d8e6eb' }} />
      <Area yAxisId="month" type="monotone" dataKey="amount" name="Collected in month" stroke="#183b62" strokeWidth={2} fill="url(#webv9CollectionFill)" isAnimationActive={false} />
      <Line yAxisId="cumulative" type="monotone" dataKey="cumulative" name="Cumulative collected" stroke="#c29a4b" strokeWidth={2} strokeDasharray="5 3" dot={false} isAnimationActive={false} />
    </ComposedChart>
  </ResponsiveContainer>;
}

function InventoryStack({ rows }: { rows: InventoryRow[] }) {
  const data = rows.map(row => ({ label: tidy(row.label), total: num(row.total), available: num(row.available), allocated: num(row.allocated) }));
  if (data.length === 0) return <ChartEmpty />;
  return <ResponsiveContainer width="100%" height="100%">
    <BarChart data={data} layout="vertical" margin={{ top: 4, right: 26, bottom: 2, left: 4 }} barCategoryGap={13}>
      <CartesianGrid stroke={VIZ_GRID} horizontal={false} />
      <XAxis type="number" tick={axisTick} axisLine={false} tickLine={false} allowDecimals={false} />
      <YAxis type="category" dataKey="label" tick={axisTick} axisLine={false} tickLine={false} width={124} tickFormatter={value => truncate(String(value), 19)} />
      <Tooltip content={<VizTooltip kind="count" />} cursor={{ fill: '#f3f8fa' }} />
      <Bar dataKey="allocated" name="Allocated / sold" stackId="inventory" fill="#183b62" maxBarSize={20} isAnimationActive={false} />
      <Bar dataKey="available" name="Available" stackId="inventory" fill="#d9c18d" radius={[0, 5, 5, 0]} maxBarSize={20} isAnimationActive={false} />
    </BarChart>
  </ResponsiveContainer>;
}

function BlockOccupancyChart({ rows }: { rows: InventoryRow[] }) {
  const data = rows.map(row => ({ label: tidy(row.label), total: num(row.total), available: num(row.available), allocated: num(row.allocated) }));
  if (data.length === 0) return <ChartEmpty />;
  return <ResponsiveContainer width="100%" height="100%">
    <BarChart data={data} margin={{ top: 14, right: 12, bottom: 2, left: 2 }} barCategoryGap={12}>
      <CartesianGrid stroke={VIZ_GRID} vertical={false} />
      <XAxis dataKey="label" tick={axisTick} axisLine={false} tickLine={false} interval={0} />
      <YAxis tick={axisTick} axisLine={false} tickLine={false} width={36} allowDecimals={false} />
      <Tooltip content={<VizTooltip kind="count" />} cursor={{ fill: '#f3f8fa' }} />
      <Bar dataKey="allocated" name="Allocated / sold" stackId="blocks" fill="#183b62" radius={[4, 4, 0, 0]} maxBarSize={40} isAnimationActive={false} />
      <Bar dataKey="available" name="Available" stackId="blocks" fill="#d9c18d" radius={[4, 4, 0, 0]} maxBarSize={40} isAnimationActive={false} />
    </BarChart>
  </ResponsiveContainer>;
}

function BookingsChart({ rows }: { rows: CountPoint[] }) {
  const data = rows.map(row => ({ ...row, count: num(row.count) }));
  if (data.length === 0) return <ChartEmpty />;
  return <ResponsiveContainer width="100%" height="100%">
    <BarChart data={data} margin={{ top: 14, right: 12, bottom: 2, left: 2 }}>
      <CartesianGrid stroke={VIZ_GRID} vertical={false} />
      <XAxis dataKey="label" tick={axisTick} axisLine={false} tickLine={false} minTickGap={8} />
      <YAxis tick={axisTick} axisLine={false} tickLine={false} width={34} allowDecimals={false} />
      <Tooltip content={<VizTooltip kind="count" />} cursor={{ fill: '#f3f8fa' }} />
      <Bar dataKey="count" name="New bookings" fill="#36a6a0" radius={[4, 4, 0, 0]} maxBarSize={30} isAnimationActive={false} />
    </BarChart>
  </ResponsiveContainer>;
}

function MembersChart({ rows }: { rows: CountPoint[] }) {
  const data = rows.map(row => ({ ...row, count: num(row.count) }));
  if (data.length === 0) return <ChartEmpty />;
  return <ResponsiveContainer width="100%" height="100%">
    <AreaChart data={data} margin={{ top: 14, right: 12, bottom: 2, left: 2 }}>
      <defs>
        <linearGradient id="webv9MemberFill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#c29a4b" stopOpacity={0.36} />
          <stop offset="100%" stopColor="#c29a4b" stopOpacity={0.02} />
        </linearGradient>
      </defs>
      <CartesianGrid stroke={VIZ_GRID} vertical={false} />
      <XAxis dataKey="label" tick={axisTick} axisLine={false} tickLine={false} minTickGap={8} />
      <YAxis tick={axisTick} axisLine={false} tickLine={false} width={34} allowDecimals={false} />
      <Tooltip content={<VizTooltip kind="count" />} cursor={{ stroke: '#d8e6eb' }} />
      <Area type="monotone" dataKey="count" name="New members" stroke="#4a89ad" strokeWidth={2.2} fill="url(#webv9MemberFill)" isAnimationActive={false} />
    </AreaChart>
  </ResponsiveContainer>;
}

function Members() {
  const [items, setItems] = useState<Customer[]>([]); const [q, setQ] = useState(''); const [loading, setLoading] = useState(true);
  const [modal, setModal] = useState(false); const [saving, setSaving] = useState(false); const [error, setError] = useState('');
  const [form, setForm] = useState({ mbrNo: '', regNo: '', formNo: '', category: '', name: '', cnicNo: '', contact1: '', city: '', address: '' });
  const load = () => { setLoading(true); api<Customer[]>(`/customers${q ? `?q=${encodeURIComponent(q)}` : ''}`).then(setItems).catch(e => setError(e.message)).finally(() => setLoading(false)); };
  useEffect(() => { load(); }, [q]);
  function update(key: keyof typeof form, value: string) { setForm(current => ({ ...current, [key]: value })); }
  async function submit(e: React.FormEvent) { e.preventDefault(); setSaving(true); setError(''); try { await api<Customer>('/customers', { method: 'POST', body: JSON.stringify({ ...form, status: 'ACTIVE' }) }); setModal(false); setForm({ mbrNo: '', regNo: '', formNo: '', category: '', name: '', cnicNo: '', contact1: '', city: '', address: '' }); load(); } catch (err) { setError(err instanceof Error ? err.message : 'Unable to register member.'); } finally { setSaving(false); } }
  return <><div className="toolbar"><div><p className="eyebrow">CUSTOMER REGISTER</p><h1>Members & customers</h1><p className="muted">Search, review and register District One members.</p></div><button className="primary" onClick={() => { setError(''); setModal(true); }}><Plus size={16} /> Add member</button></div>{error && !modal && <div className="error page-error">{error}</div>}<div className="panel"><div className="search"><Search size={18} /><input placeholder="Search by name or MBR number…" value={q} onChange={e => setQ(e.target.value)} /></div>{loading ? <div className="empty">Loading customer records…</div> : items.length === 0 ? <div className="empty">No customer records match this search.</div> : <div className="table-wrap"><table><thead><tr><th>MBR No.</th><th>Customer</th><th>CNIC</th><th>Contact</th><th>City</th><th>Status</th></tr></thead><tbody>{items.map(c => <tr key={c.id}><td className="code">{c.mbrNo}</td><td><b>{c.name}</b><small>{c.regNo || c.formNo || 'No secondary identifier'}</small></td><td>{c.cnicNo || '—'}</td><td>{c.contact1 || '—'}</td><td>{c.city || '—'}</td><td><span className="badge">{c.status}</span></td></tr>)}</tbody></table></div>}</div>{modal && <div className="modal-backdrop"><form className="modal" onSubmit={submit}><div className="modal-head"><div><p className="eyebrow">MEMBER REGISTRATION</p><h2>Register a member</h2></div><button type="button" className="icon" onClick={() => setModal(false)}><X /></button></div><p className="muted">This creates a member and its separate registration record in the existing District One database.</p><div className="form-grid"><label>MBR number<input required value={form.mbrNo} onChange={e => update('mbrNo', e.target.value)} /></label><label>Registration number<input required value={form.regNo} onChange={e => update('regNo', e.target.value)} /></label><label>Form number<input value={form.formNo} onChange={e => update('formNo', e.target.value)} /></label><label>Member name<input required value={form.name} onChange={e => update('name', e.target.value)} /></label><label>CNIC number<input required value={form.cnicNo} onChange={e => update('cnicNo', e.target.value)} /></label><label>Primary contact<input value={form.contact1} onChange={e => update('contact1', e.target.value)} /></label><label>City<input value={form.city} onChange={e => update('city', e.target.value)} /></label><label className="wide">Address<input value={form.address} onChange={e => update('address', e.target.value)} /></label></div>{error && <div className="error">{error}</div>}<div className="modal-actions"><button type="button" className="secondary" onClick={() => setModal(false)}>Cancel</button><button className="primary" disabled={saving}>{saving ? 'Saving…' : 'Save member'}</button></div></form></div>}</>;
}
function DataPanel({ title, endpoint, columns }: { title: string; endpoint: string; columns: string[] }) {
  const [rows, setRows] = useState<Record<string, unknown>[]>([]); const [error, setError] = useState(''); const [loading, setLoading] = useState(true);
  useEffect(() => { setLoading(true); setError(''); api<Record<string, unknown>[]>(endpoint).then(setRows).catch(e => setError(e.message)).finally(() => setLoading(false)); }, [endpoint]);
  return <><div className="toolbar"><div><p className="eyebrow">LIVE MYSQL REGISTER</p><h1>{title}</h1><p className="muted">Operational records from the District One database.</p></div></div><div className="panel">{error && <div className="error">{error}</div>}{loading ? <div className="empty">Loading live records…</div> : rows.length === 0 && !error ? <div className="empty">No records found.</div> : !error && <div className="table-wrap"><table><thead><tr>{columns.map(c => <th key={c}>{c.replace(/([A-Z])/g, ' $1')}</th>)}</tr></thead><tbody>{rows.map((row, index) => <tr key={index}>{columns.map(c => <td key={c}>{String(row[c] ?? '—')}</td>)}</tr>)}</tbody></table></div>}</div></>;
}
type ProjectRecord = { id: number; projectCode: string; projectName: string; projectDescription?: string; location?: string; city?: string; status: string; startDate?: string; remarks?: string; active: boolean; blockCount: number; activeBlockCount: number; plotCount: number };
type BlockRecord = { id: number; blockCode: string; blockName: string; projectId: number; active: boolean; plotCount: number };
function Projects() {
  const [projects, setProjects] = useState<ProjectRecord[]>([]); const [selected, setSelected] = useState<ProjectRecord | null>(null); const [modal, setModal] = useState(false); const [blockModal, setBlockModal] = useState(false); const [editing, setEditing] = useState<ProjectRecord | null>(null); const [error, setError] = useState(''); const [loading, setLoading] = useState(true);
  const blank = { projectName: '', projectDescription: '', location: '', city: '', status: 'ACTIVE', startDate: '', remarks: '', active: true };
  const [form, setForm] = useState<Record<string, string | boolean>>(blank);
  const load = () => { setLoading(true); api<ProjectRecord[]>('/projects').then(setProjects).catch(e => setError(e.message)).finally(() => setLoading(false)); };
  useEffect(() => { load(); }, []);
  async function save(e: React.FormEvent) { e.preventDefault(); setError(''); try { const payload = { projectName: form.projectName, projectDescription: form.projectDescription, location: form.location, city: form.city, status: form.status, startDate: form.startDate || null, remarks: form.remarks, active: form.active }; await api(editing ? `/projects/${editing.id}` : '/projects', { method: editing ? 'PUT' : 'POST', body: JSON.stringify(payload) }); setModal(false); setEditing(null); setForm(blank); load(); } catch (e) { setError(e instanceof Error ? e.message : 'Unable to save project.'); } }
  function openEdit(project: ProjectRecord) { setEditing(project); setForm({ projectName: project.projectName, projectDescription: project.projectDescription || '', location: project.location || '', city: project.city || '', status: project.status, startDate: project.startDate || '', remarks: project.remarks || '', active: project.active }); setError(''); setModal(true); }
  const totalBlocks = projects.reduce((count, project) => count + Number(project.blockCount || 0), 0);
  const totalPlots = projects.reduce((count, project) => count + Number(project.plotCount || 0), 0);
  const [inventoryProjectId, setInventoryProjectId] = useState('ALL');
  const inventoryEndpoint = inventoryProjectId === 'ALL' ? '/plots/inventory' : `/projects/${inventoryProjectId}/inventory`;
  return <><div className="toolbar"><div><p className="eyebrow">PROJECT MANAGEMENT</p><h1>Projects</h1><p className="muted">Manage District One projects, blocks and plot inventory.</p></div><button className="primary" onClick={() => { setEditing(null); setForm(blank); setError(''); setModal(true); }}><Plus size={16} /> New Project</button></div>{error && !modal && <div className="error page-error">{error}</div>}<div className="project-stat-grid"><div className="stat"><Building2 size={18} className="stat-icon" /><span>Projects</span><strong>{projects.length}</strong><small>Configured developments</small></div><div className="stat"><Layers3 size={18} className="stat-icon" /><span>Blocks</span><strong>{totalBlocks}</strong><small>Across configured projects</small></div><div className="stat"><MapPin size={18} className="stat-icon" /><span>Project plots</span><strong>{totalPlots}</strong><small>Plots assigned to a project</small></div></div><div className="panel"><div className="panel-head"><div><p className="eyebrow">PROJECT REGISTER</p><h3>Existing projects</h3></div><button className="secondary" onClick={load}><RefreshCw size={15} /> Refresh</button></div>{loading ? <div className="empty">Loading projects…</div> : projects.length === 0 ? <div className="empty">No projects configured yet. Create the first project to begin.</div> : <div className="table-wrap"><table><thead><tr><th>Code</th><th>Project</th><th>Location</th><th>Status</th><th>Start date</th><th>Blocks</th><th>Plots</th><th>Actions</th></tr></thead><tbody>{projects.map(p => <tr key={p.id}><td className="code">{p.projectCode}</td><td><b>{p.projectName}</b><small>{p.projectDescription || 'No description'}</small></td><td>{[p.city, p.location].filter(Boolean).join(' / ') || '—'}</td><td><span className="badge">{p.status}</span></td><td>{p.startDate || '—'}</td><td>{p.blockCount} <small>{p.activeBlockCount} active</small></td><td>{p.plotCount}</td><td><button className="secondary compact" onClick={() => setSelected(p)}><Eye size={13} /> View</button> <button className="secondary compact" onClick={() => openEdit(p)}><Pencil size={13} /> Edit</button></td></tr>)}</tbody></table></div>}</div><div className="panel project-inventory"><div className="panel-head"><div><p className="eyebrow">LIVE MYSQL REGISTER</p><h3>Plot Inventory</h3><p className="muted">Inventory is shown using each plot’s existing block-to-project relationship.</p></div><label>Project<select value={inventoryProjectId} onChange={e=>setInventoryProjectId(e.target.value)}><option value="ALL">All projects and unassigned</option>{projects.map(project=><option key={project.id} value={project.id}>{project.projectCode} — {project.projectName}</option>)}</select></label></div><DataPanel title="" endpoint={inventoryEndpoint} columns={['projectCode','projectName','block','blockName','plotNo','sizeMarla','category','status']} /></div>{selected && <ProjectDetails project={selected} onClose={() => setSelected(null)} onBlock={() => setBlockModal(true)} />}{modal && <ProjectForm form={form} setForm={setForm} editing={editing} error={error} onClose={() => { setModal(false); setEditing(null); }} onSubmit={save} />}{blockModal && selected && <BlockWorkspace project={selected} onClose={() => { setBlockModal(false); setSelected(null); load(); }} />}</>;
}
function ProjectForm({ form, setForm, editing, error, onClose, onSubmit }: { form: Record<string, string | boolean>; setForm: React.Dispatch<React.SetStateAction<Record<string, string | boolean>>>; editing: ProjectRecord | null; error: string; onClose: () => void; onSubmit: (e: React.FormEvent) => void }) { const update = (key: string, value: string | boolean) => setForm(current => ({ ...current, [key]: value })); return <div className="modal-backdrop"><form className="modal project-modal" onSubmit={onSubmit}><div className="modal-head"><div><p className="eyebrow">PROJECT MANAGEMENT</p><h2>{editing ? 'Edit project' : 'New Project'}</h2><p className="muted">{editing ? `Project Code ${editing.projectCode} remains unchanged.` : 'The system will generate a unique Project Code when saved.'}</p></div><button type="button" className="icon" onClick={onClose}><X /></button></div>{editing && <div className="readonly-code"><span>Project Code</span><b>{editing.projectCode}</b></div>}<div className="form-grid"><label>Project Name<input required maxLength={160} value={String(form.projectName || '')} onChange={e => update('projectName', e.target.value)} /></label><label>Location<input maxLength={255} value={String(form.location || '')} onChange={e => update('location', e.target.value)} /></label><label>City<input maxLength={120} value={String(form.city || '')} onChange={e => update('city', e.target.value)} /></label><label>Status<select value={String(form.status || 'ACTIVE')} onChange={e => update('status', e.target.value)}><option>ACTIVE</option><option>PLANNING</option><option>COMPLETED</option><option>INACTIVE</option></select></label><label>Start Date<input type="date" value={String(form.startDate || '')} onChange={e => update('startDate', e.target.value)} /></label><label className="wide">Project Description<textarea maxLength={1000} value={String(form.projectDescription || '')} onChange={e => update('projectDescription', e.target.value)} /></label><label className="wide">Remarks<textarea maxLength={1000} value={String(form.remarks || '')} onChange={e => update('remarks', e.target.value)} /></label><label className="wide status-check"><input type="checkbox" checked={Boolean(form.active)} onChange={e => update('active', e.target.checked)} /> Active project</label></div>{error && <div className="error">{error}</div>}<div className="modal-actions"><button type="button" className="secondary" onClick={onClose}>Cancel</button><button className="primary">{editing ? 'Save changes' : 'Create project'}</button></div></form></div>; }
function ProjectDetails({ project, onClose, onBlock }: { project: ProjectRecord; onClose: () => void; onBlock: () => void }) { return <div className="modal-backdrop"><section className="modal project-modal"><div className="modal-head"><div><p className="eyebrow">PROJECT DETAILS</p><h2>{project.projectName}</h2><p className="muted">{project.projectCode} · {[project.city, project.location].filter(Boolean).join(' / ') || 'Location not specified'}</p></div><button className="icon" onClick={onClose}><X /></button></div><div className="detail-grid"><div><span>Code</span><b>{project.projectCode}</b></div><div><span>Status</span><b>{project.status}</b></div><div><span>Start Date</span><b>{project.startDate || '—'}</b></div><div><span>Active</span><b>{project.active ? 'Yes' : 'No'}</b></div><div><span>Blocks</span><b>{project.blockCount} total · {project.activeBlockCount} active</b></div><div><span>Plots</span><b>{project.plotCount}</b></div></div><div className="project-description"><b>Description</b><p>{project.projectDescription || 'No description provided.'}</p><b>Remarks</b><p>{project.remarks || 'No remarks.'}</p></div><div className="modal-actions"><button className="primary" onClick={onBlock}><Layers3 size={15} /> Manage Blocks</button><button className="secondary" onClick={onClose}>Close</button></div></section></div>; }
function BlockWorkspace({ project, onClose }: { project: ProjectRecord; onClose: () => void }) {
  const [rows, setRows] = useState<BlockRecord[]>([]);
  const [unassigned, setUnassigned] = useState<Omit<BlockRecord, 'projectId'>[]>([]);
  const [form, setForm] = useState({ blockName: '', active: true });
  const [editing, setEditing] = useState<BlockRecord | null>(null);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const load = () => Promise.all([
    api<BlockRecord[]>(`/projects/${project.id}/blocks`),
    api<Omit<BlockRecord, 'projectId'>[]>('/projects/blocks/unassigned')
  ]).then(([projectBlocks, unassignedBlocks]) => {
    setRows(projectBlocks);
    setUnassigned(unassignedBlocks);
    setError('');
  }).catch(e => setError(e instanceof Error ? e.message : 'Unable to load project blocks.'));
  useEffect(() => { load(); }, [project.id]);
  async function save(e: React.FormEvent) {
    e.preventDefault(); setSaving(true); setError('');
    try {
      await api(editing ? `/projects/blocks/${editing.id}` : `/projects/${project.id}/blocks`, {
        method: editing ? 'PUT' : 'POST', body: JSON.stringify(form)
      });
      setForm({ blockName: '', active: true }); setEditing(null); await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unable to save block.');
    } finally { setSaving(false); }
  }
  async function toggle(row: BlockRecord) {
    setError('');
    try {
      await api(`/projects/blocks/${row.id}/status`, { method: 'PATCH', body: JSON.stringify({ active: !row.active }) });
      await load();
    } catch (e) { setError(e instanceof Error ? e.message : 'Unable to update block.'); }
  }
  async function assign(row: Omit<BlockRecord, 'projectId'>) {
    setError('');
    try {
      await api(`/projects/${project.id}/blocks/${row.id}/assign`, { method: 'PATCH' });
      await load();
    } catch (e) { setError(e instanceof Error ? e.message : 'Unable to assign block.'); }
  }
  return <div className="modal-backdrop"><section className="modal project-modal"><div className="modal-head"><div><p className="eyebrow">BLOCKS / {project.projectCode}</p><h2>{project.projectName} blocks</h2><p className="muted">Block codes are generated automatically and remain unchanged.</p></div><button className="icon" onClick={onClose}><X /></button></div><form className="inline-form" onSubmit={save}><label>Block Name<input required maxLength={100} value={form.blockName} onChange={e => setForm({ ...form, blockName: e.target.value })} /></label><button className="primary" disabled={saving}>{saving ? 'Saving…' : editing ? 'Update block' : 'Add Block'}</button>{editing && <button type="button" className="secondary" onClick={() => { setEditing(null); setForm({ blockName: '', active: true }); }}>Cancel edit</button>}</form>{error && <div className="error">{error}</div>}<div className="table-wrap block-table"><table><thead><tr><th>Block Code</th><th>Block Name</th><th>Plots</th><th>Status</th><th>Actions</th></tr></thead><tbody>{rows.map(row => <tr key={row.id}><td className="code">{row.blockCode}</td><td>{row.blockName}</td><td>{row.plotCount}</td><td><span className="badge">{row.active ? 'ACTIVE' : 'INACTIVE'}</span></td><td><button className="secondary compact" onClick={() => { setEditing(row); setForm({ blockName: row.blockName, active: row.active }); }}><Pencil size={13} /> Edit</button> <button className="secondary compact" onClick={() => toggle(row)}>{row.active ? 'Deactivate' : 'Activate'}</button></td></tr>)}</tbody></table></div><div className="project-description"><b>Unassigned existing blocks</b><p>Link a block only after confirming it belongs to this project. Existing block and plot data are preserved.</p></div>{unassigned.length === 0 ? <div className="empty">No unassigned blocks.</div> : <div className="table-wrap block-table"><table><thead><tr><th>Block Code</th><th>Block Name</th><th>Plots</th><th>Action</th></tr></thead><tbody>{unassigned.map(row => <tr key={row.id}><td className="code">{row.blockCode}</td><td>{row.blockName}</td><td>{row.plotCount}</td><td><button className="secondary compact" onClick={() => assign(row)}>Link to project</button></td></tr>)}</tbody></table></div>}<div className="modal-actions"><button className="secondary" onClick={onClose}>Close</button></div></section></div>;
}
type SalesBooking = {
  id: number; bookingNo: string; bookingDate: string; status: string;
  registrationId: number; regNo: string; memberId: number; mbrNo: string; memberName: string;
  plotId: number; plotNo: string; sizeMarla: number | null; usageType: string | null;
  blockName: string; projectName: string | null;
};
type AvailablePlot = { plotId: number; plotNo: string; sizeMarla: number | null; usageType: string | null; status: string; blockName: string; projectName: string | null; projectCode?: string };
type RegistrationRecord = { registrationId: number; regNo: string; formNo?: string; memberId: number; mbrNo: string; memberName: string; registrationStatus?: string };
type PlotInventoryRecord = {
  id: number; plotNo: string; blockId: number; block: string; blockName: string;
  sizeMarla: number | null; type: string | null; category: string | null;
  plotStatus: string; status: string; availability: 'AVAILABLE' | 'COMMITTED' | 'UNAVAILABLE';
  projectName?: string | null; bookingNo?: string | null; ownerName?: string | null;
  mbrNo?: string | null; regNo?: string | null; lastTransferDate?: string | null;
  cancellationDate?: string | null; mergeRole?: string | null;
};
type PlotInventoryBlock = { id: number; blockCode: string; blockName: string };
type PlotInventoryForm = { blockId: string; sizeMarla: string; type: string; category: string; status: string };
function SalesWorkspace() {
  const [tab,setTab]=useState('Plot Inventory');
  const tabs=['Plot Inventory','Bookings','Transfers','Cancellations','Plot Merges'];
  return <><div className="toolbar"><div><p className="eyebrow">SALES & PLOT OPERATIONS</p><h1>Sales</h1><p className="muted">Manage live plot allocations, transfers, cancellations and plot merges.</p></div></div><div className="finance-nav-grid sales-nav-grid">{tabs.map(item=><button key={item} className={tab===item?'finance-nav active':'finance-nav'} onClick={()=>setTab(item)}><WalletCards size={16}/><span>{item}</span></button>)}</div>{tab==='Plot Inventory'&&<PlotInventoryWorkspace/>}{tab==='Bookings'&&<SalesBookings/>}{tab==='Transfers'&&<SalesTransfers/>}{tab==='Cancellations'&&<SalesCancellations/>}{tab==='Plot Merges'&&<SalesMerges/>}</>;
}
function PlotInventoryWorkspace() {
  const [rows,setRows]=useState<PlotInventoryRecord[]>([]);
  const [blocks,setBlocks]=useState<PlotInventoryBlock[]>([]);
  const [search,setSearch]=useState('');
  const [availability,setAvailability]=useState('ALL');
  const [loading,setLoading]=useState(true);
  const [error,setError]=useState('');
  const [open,setOpen]=useState(false);
  const [editing,setEditing]=useState<PlotInventoryRecord|null>(null);
  const [saving,setSaving]=useState(false);
  const empty:PlotInventoryForm={blockId:'',sizeMarla:'',type:'Residential',category:'',status:'AVAILABLE'};
  const [form,setForm]=useState<PlotInventoryForm>(empty);
  const load=()=>{
    setLoading(true);setError('');
    Promise.all([
      api<PlotInventoryRecord[]>(`/plots/inventory?q=${encodeURIComponent(search)}&availability=${encodeURIComponent(availability)}`),
      api<PlotInventoryBlock[]>('/plots/inventory/blocks')
    ]).then(([plotRows,blockRows])=>{setRows(plotRows);setBlocks(blockRows);})
      .catch(e=>setError(e instanceof Error?e.message:'Unable to load plot inventory.'))
      .finally(()=>setLoading(false));
  };
  useEffect(()=>{load();},[search,availability]);
  function startCreate(){
    setEditing(null);
    setForm({...empty,blockId:blocks.length?String(blocks[0].id):''});
    setError('');
    setOpen(true);
  }
  function startEdit(plot:PlotInventoryRecord){
    setEditing(plot);
    setForm({blockId:String(plot.blockId),sizeMarla:plot.sizeMarla==null?'':String(plot.sizeMarla),
      type:plot.type||'Residential',category:plot.category||'',status:plot.plotStatus||'AVAILABLE'});
    setError('');
    setOpen(true);
  }
  async function save(e:React.FormEvent){
    e.preventDefault();setSaving(true);setError('');
    try{
      const payload={blockId:Number(form.blockId),sizeMarla:form.sizeMarla?Number(form.sizeMarla):null,
        type:form.type||null,category:form.category.trim()||null,status:form.status};
      await api(editing?`/plots/inventory/${editing.id}`:'/plots/inventory',{
        method:editing?'PUT':'POST',body:JSON.stringify(payload)
      });
      setOpen(false);setEditing(null);load();
    }catch(e){setError(e instanceof Error?e.message:'Unable to save plot inventory.');}
    finally{setSaving(false);}
  }
  const counts=rows.reduce((result,plot)=>{result[plot.availability]=(result[plot.availability]||0)+1;return result;},
    {AVAILABLE:0,COMMITTED:0,UNAVAILABLE:0} as Record<string,number>);
  return <>
    <section className="plot-inventory">
      <div className="panel plot-inventory-panel">
        <div className="panel-head plot-inventory-heading">
          <div><p className="eyebrow">LIVE PLOT REGISTER</p><h3>Plot Inventory</h3>
            <p className="muted">New plot numbers are generated in District One format and cannot be edited.</p></div>
          <button className="primary" onClick={startCreate} disabled={!blocks.length} title={!blocks.length?'No active blocks are available':''}><Plus size={15}/> Add Plot</button>
        </div>
        <div className="plot-inventory-stats">
          <div><span>Available / vacant</span><strong>{counts.AVAILABLE}</strong></div>
          <div><span>Committed</span><strong>{counts.COMMITTED}</strong></div>
          <div><span>Unavailable</span><strong>{counts.UNAVAILABLE}</strong></div>
        </div>
        <div className="plot-inventory-filters">
          <label className="search"><Search size={17}/><input value={search} onChange={e=>setSearch(e.target.value)} placeholder="Search plot number or block"/></label>
          <label>Availability<select value={availability} onChange={e=>setAvailability(e.target.value)}>
            <option value="ALL">All inventory</option><option value="AVAILABLE">Available / vacant</option>
            <option value="COMMITTED">Committed</option><option value="UNAVAILABLE">Other unavailable</option>
          </select></label>
          <button className="secondary" onClick={load}><RefreshCw size={14}/> Refresh</button>
        </div>
        {error&&!open&&<div className="error page-error">{error}</div>}
        {loading?<div className="empty">Loading plot inventory…</div>:rows.length===0?<div className="empty">No plot records match these filters.</div>:
          <div className="table-wrap"><table><thead><tr><th>Plot / Unit</th><th>Project / Block</th><th>Size</th><th>Category</th><th>Classification</th><th>Status</th><th>Availability</th><th>Sale / Party</th><th>Actions</th></tr></thead>
            <tbody>{rows.map(plot=><tr key={plot.id}>
              <td className="code">{plot.plotNo}</td>
              <td>{plot.projectName||'—'}<small>{plot.block} · {plot.blockName}</small></td>
              <td>{plot.sizeMarla==null?'—':`${plot.sizeMarla} Marla`}</td>
              <td>{plot.category||'—'}</td><td>{plot.type||'—'}</td>
              <td><span className={`plot-state ${plot.availability.toLowerCase()}`}>{plot.status}</span></td>
              <td><span className={`plot-state ${plot.availability.toLowerCase()}`}>{plot.availability==='AVAILABLE'?'VACANT':plot.availability}</span></td>
              <td>{plot.bookingNo||'—'}<small>{plot.ownerName||plot.regNo||'No current sale party'}</small></td>
              <td><button className="secondary compact" onClick={()=>startEdit(plot)} disabled={plot.status==='MERGED'}><Pencil size={13}/> Edit</button></td>
            </tr>)}</tbody>
          </table></div>}
      </div>
    </section>
    {open&&<div className="modal-backdrop"><form className="modal project-modal" onSubmit={save}>
      <div className="modal-head"><div><p className="eyebrow">SALES / PLOT INVENTORY</p><h2>{editing?'Edit plot':'Add plot'}</h2>
        <p className="muted">{editing?'Plot number remains unchanged.':'The system generates a unique DOL-xxxxx plot number.'}</p></div>
        <button type="button" className="icon" onClick={()=>setOpen(false)}><X/></button>
      </div>
      {editing&&<div className="readonly-code"><span>Plot / Unit number</span><b>{editing.plotNo}</b></div>}
      <div className="form-grid">
        <label>Block<select required value={form.blockId} onChange={e=>setForm({...form,blockId:e.target.value})}>
          <option value="">Select block</option>{blocks.map(block=><option key={block.id} value={block.id}>{block.blockCode} · {block.blockName}</option>)}
        </select></label>
        <label>Plot size (Marla)<input type="number" min="0.01" max="9999.99" step="0.01" value={form.sizeMarla} onChange={e=>setForm({...form,sizeMarla:e.target.value})}/></label>
        <label>Domestic / Commercial<select value={form.type} onChange={e=>setForm({...form,type:e.target.value})}>
          <option value="Residential">Residential</option><option value="Commercial">Commercial</option>
        </select></label>
        <label>Category<input maxLength={100} value={form.category} onChange={e=>setForm({...form,category:e.target.value})} placeholder="General, Corner, Facing Park…"/></label>
        {editing&&<label>Status<select value={form.status} onChange={e=>setForm({...form,status:e.target.value})}>
          <option value="AVAILABLE">Available</option><option value="UNALLOCATED">Unallocated</option><option value="OPEN">Open</option>
          <option value="ALLOCATED">Allocated</option><option value="BOOKED">Booked</option><option value="SOLD">Sold</option><option value="INACTIVE">Inactive</option>
        </select></label>}
      </div>
      {error&&<div className="error">{error}</div>}
      <div className="modal-actions"><button type="button" className="secondary" onClick={()=>setOpen(false)}>Cancel</button>
        <button className="primary" disabled={saving||!blocks.length}>{saving?'Saving…':editing?'Save changes':'Create plot'}</button></div>
    </form></div>}
  </>;
}
function SalesBookings(){
  const [rows,setRows]=useState<SalesBooking[]>([]);const [registrations,setRegistrations]=useState<RegistrationRecord[]>([]);const [plots,setPlots]=useState<AvailablePlot[]>([]);
  const [status,setStatus]=useState('ALL');const [loading,setLoading]=useState(true);const [error,setError]=useState('');const [open,setOpen]=useState(false);const [saving,setSaving]=useState(false);
  const blank={registrationId:'',plotId:''};const [form,setForm]=useState(blank);
  const load=()=>{setLoading(true);Promise.all([api<SalesBooking[]>('/sales/bookings?status='+status),api<RegistrationRecord[]>('/sales/registrations'),api<AvailablePlot[]>('/sales/available-plots')]).then(([b,r,p])=>{setRows(b);setRegistrations(r);setPlots(p)}).catch(e=>setError(e.message)).finally(()=>setLoading(false));};
  useEffect(() => { load(); }, [status]);
  async function save(e:React.FormEvent){e.preventDefault();setSaving(true);setError('');try{await api('/sales/bookings',{method:'POST',body:JSON.stringify({registrationId:Number(form.registrationId),plotId:Number(form.plotId)})});setOpen(false);setForm(blank);load()}catch(e){setError(e instanceof Error?e.message:'Unable to allocate plot.')}finally{setSaving(false)}}
  return <><div className="panel"><div className="panel-head"><div><p className="eyebrow">EXISTING SALES ALLOCATIONS</p><h3>Bookings</h3></div><div className="finance-filter"><label>Status<select value={status} onChange={e=>setStatus(e.target.value)}><option value="ALL">All</option><option value="ALLOCATED">Allocated</option><option value="CANCELLED">Cancelled</option></select></label><button className="primary" disabled={!plots.length} title={!plots.length?'No unallocated plots are currently available':''} onClick={()=>{setForm(blank);setOpen(true)}}><Plus size={15}/> Allocate Plot</button></div></div>{error&&<div className="error page-error">{error}</div>}{loading?<div className="empty">Loading bookings…</div>:<div className="table-wrap"><table><thead><tr><th>Booking</th><th>Recorded</th><th>Registration</th><th>Member</th><th>Plot</th><th>Project / Block</th><th>Status</th></tr></thead><tbody>{rows.map(r=><tr key={r.id}><td className="code">{r.bookingNo}</td><td>{r.bookingDate?String(r.bookingDate).slice(0,10):'—'}</td><td>{r.regNo||'—'}</td><td><b>{r.memberName}</b><small>{r.mbrNo}</small></td><td>{r.plotNo}<small>{r.sizeMarla??'—'} marla · {r.usageType||'—'}</small></td><td>{r.projectName||'—'}<small>{r.blockName}</small></td><td><span className="badge">{r.status}</span></td></tr>)}</tbody></table></div>}</div>{open&&<div className="modal-backdrop"><form className="modal project-modal" onSubmit={save}><div className="modal-head"><div><p className="eyebrow">NEW SALES ALLOCATION</p><h2>Allocate Plot</h2><p className="muted">The existing sales register stores the member registration, plot, status and source snapshots. Prices and instalment plans are not part of its current schema.</p></div><button type="button" className="icon" onClick={()=>setOpen(false)}><X/></button></div><div className="form-grid"><label className="wide">Member registration<select required value={form.registrationId} onChange={e=>setForm({...form,registrationId:e.target.value})}><option value="">Select registration</option>{registrations.map(r=><option key={r.registrationId} value={r.registrationId}>{r.regNo} — {r.memberName} ({r.mbrNo})</option>)}</select></label><label className="wide">Available plot<select required value={form.plotId} onChange={e=>setForm({...form,plotId:e.target.value})}><option value="">Select plot</option>{plots.map(p=><option key={p.plotId} value={p.plotId}>{p.plotNo} — {p.blockName} — {p.projectName||'—'} — {p.sizeMarla??'—'} marla</option>)}</select></label></div><div className="modal-actions"><button type="button" className="secondary" onClick={()=>setOpen(false)}>Cancel</button><button className="primary" disabled={saving||!plots.length}>{saving?'Saving…':'Allocate Plot'}</button></div></form></div>}</>;
}function SalesTransfers(){
  const [rows,setRows]=useState<Record<string,unknown>[]>([]); const [bookings,setBookings]=useState<SalesBooking[]>([]); const [members,setMembers]=useState<Customer[]>([]); const [open,setOpen]=useState(false); const [error,setError]=useState(''); const [saving,setSaving]=useState(false); const [form,setForm]=useState({bookingId:'',fromMemberId:'',toMemberId:'',transferDate:new Date().toISOString().slice(0,10),notes:''});
  const load=()=>Promise.all([api<Record<string,unknown>[]>('/sales/transfers'),api<SalesBooking[]>('/sales/bookings?status=ALLOCATED'),api<Customer[]>('/customers')]).then(([r,b,m])=>{setRows(r);setBookings(b);setMembers(m)}).catch(e=>setError(e.message)); useEffect(() => { load(); }, []);
  async function save(e:React.FormEvent){e.preventDefault();setSaving(true);try{await api('/sales/transfers',{method:'POST',body:JSON.stringify({...form,bookingId:Number(form.bookingId),fromMemberId:Number(form.fromMemberId),toMemberId:Number(form.toMemberId)})});setOpen(false);load()}catch(e){setError(e instanceof Error?e.message:'Unable to transfer booking.')}finally{setSaving(false)}}
  return <div className="panel"><div className="panel-head"><div><p className="eyebrow">OWNERSHIP TRANSFERS</p><h3>Plot Transfers</h3></div><button className="primary" onClick={()=>setOpen(true)}><Plus size={15}/> New Transfer</button></div>{error&&<div className="error page-error">{error}</div>}<div className="table-wrap"><table><thead><tr><th>Date</th><th>Booking</th><th>Plot</th><th>From</th><th>To</th><th>Status</th></tr></thead><tbody>{rows.map((r,i)=><tr key={i}><td>{String(r.transferDate||'').slice(0,10)}</td><td className="code">{String(r.bookingNo||'—')}</td><td>{String(r.plotNo||'—')}<small>{String(r.blockName||'—')}</small></td><td>{String(r.fromMemberName||'—')}<small>{String(r.fromMbrNo||'')}</small></td><td>{String(r.toMemberName||'—')}<small>{String(r.toMbrNo||'')}</small></td><td><span className="badge">{String(r.status||'—')}</span></td></tr>)}</tbody></table></div>{open&&<div className="modal-backdrop"><form className="modal" onSubmit={save}><div className="modal-head"><div><p className="eyebrow">TRANSFER</p><h2>Transfer Ownership</h2></div><button type="button" className="icon" onClick={()=>setOpen(false)}><X/></button></div><div className="form-grid"><label className="wide">Booking<select required value={form.bookingId} onChange={e=>{const b=bookings.find(x=>String(x.id)===e.target.value);setForm({...form,bookingId:e.target.value,fromMemberId:b?String(b.memberId):''})}}><option value="">Select booking</option>{bookings.map(b=><option key={b.id} value={b.id}>{b.bookingNo} — {b.plotNo} — {b.memberName}</option>)}</select></label><label>Current Member<select required value={form.fromMemberId} onChange={e=>setForm({...form,fromMemberId:e.target.value})}><option value="">Select current owner</option>{members.map(m=><option key={m.id} value={m.id}>{m.name} — {m.mbrNo}</option>)}</select></label><label>New Member<select required value={form.toMemberId} onChange={e=>setForm({...form,toMemberId:e.target.value})}><option value="">Select new owner</option>{members.filter(m=>String(m.id)!==form.fromMemberId).map(m=><option key={m.id} value={m.id}>{m.name} — {m.mbrNo}</option>)}</select></label><label>Transfer Date<input required type="date" value={form.transferDate} onChange={e=>setForm({...form,transferDate:e.target.value})}/></label><label className="wide">Notes<input value={form.notes} onChange={e=>setForm({...form,notes:e.target.value})}/></label></div><div className="modal-actions"><button type="button" className="secondary" onClick={()=>setOpen(false)}>Cancel</button><button className="primary" disabled={saving}>{saving?'Saving…':'Approve Transfer'}</button></div></form></div>}</div>;
}
function SalesCancellations(){
  const [rows,setRows]=useState<Record<string,unknown>[]>([]);const [bookings,setBookings]=useState<SalesBooking[]>([]);
  const [selected,setSelected]=useState<Record<string,unknown>|null>(null);const [open,setOpen]=useState(false);
  const [error,setError]=useState('');const [saving,setSaving]=useState(false);
  const [decision,setDecision]=useState('');const [deductionIds,setDeductionIds]=useState<number[]>([]);
  const [payoutReference,setPayoutReference]=useState('');
  const [form,setForm]=useState({bookingId:'',cancellationDate:new Date().toISOString().slice(0,10),reason:'',remarks:''});
  const canReview=canPerformAction('Plots & Sales','APPROVE');
  const load=()=>Promise.all([api<Record<string,unknown>[]>('/sales/cancellations'),api<SalesBooking[]>('/sales/bookings?status=ALLOCATED')]).then(([r,b])=>{setRows(r);setBookings(b)}).catch(e=>setError(e.message));
  useEffect(()=>{load()},[]);
  async function openDetail(id:number){setError('');try{setSelected(await api<Record<string,unknown>>(`/sales/cancellations/${id}`));setDecision('');setDeductionIds([])}catch(e){setError(e instanceof Error?e.message:'Unable to load cancellation details.')}}
  async function save(e:React.FormEvent){e.preventDefault();setSaving(true);setError('');try{const result=await api<{cancellationId:number}>('/sales/cancellations',{method:'POST',body:JSON.stringify({...form,bookingId:Number(form.bookingId)})});setOpen(false);await load();await openDetail(result.cancellationId)}catch(e){setError(e instanceof Error?e.message:'Unable to submit cancellation request.')}finally{setSaving(false)}}
  async function decide(action:'approve'|'reject'){if(!selected)return;setSaving(true);setError('');try{await api(`/sales/cancellations/${String(selected.id)}/${action}`,{method:'POST',body:JSON.stringify(action==='approve'?{deductionAdjustmentIds:deductionIds,remarks:decision}:{remarks:decision})});await load();await openDetail(Number(selected.id))}catch(e){setError(e instanceof Error?e.message:'Unable to update cancellation.')}finally{setSaving(false)}}
  async function disburse(){if(!selected)return;setSaving(true);setError('');try{await api(`/sales/cancellations/${String(selected.id)}/refund/disburse`,{method:'POST',body:JSON.stringify({payoutReference,remarks:decision})});await openDetail(Number(selected.id));await load()}catch(e){setError(e instanceof Error?e.message:'Unable to record refund disbursement.')}finally{setSaving(false)}}
  const financial=(selected?.financialPosition||{}) as Record<string,unknown>;
  const apps=Array.isArray(financial.postedApplications)?financial.postedApplications as Record<string,unknown>[]:[];
  const transfersIn=Array.isArray(financial.salesTransfersIn)?financial.salesTransfersIn as Record<string,unknown>[]:[];
  const transfersOut=Array.isArray(financial.salesTransfersOut)?financial.salesTransfersOut as Record<string,unknown>[]:[];
  const receivables=Array.isArray(financial.receivables)?financial.receivables as Record<string,unknown>[]:[];
  const eligible=Array.isArray(financial.eligibleDeductions)?financial.eligibleDeductions as Record<string,unknown>[]:[];
  const refund=Math.max(0,Number(financial.postedAmount||0)-eligible.filter(x=>deductionIds.includes(Number(x.adjustmentId))).reduce((n,x)=>n+Number(x.amount||0),0));
  const events=Array.isArray(selected?.events)?selected!.events as Record<string,unknown>[]:[];
  const refundCase=(selected?.refund||{}) as Record<string,unknown>;
  return <div className="panel"><div className="panel-head"><div><p className="eyebrow">BOOKING CANCELLATIONS</p><h3>Cancellations</h3><p className="muted">Financial effects occur only after authorized approval.</p></div><button className="primary" onClick={()=>setOpen(true)}><Plus size={15}/> New Cancellation Request</button></div>
    {error&&<div className="error page-error">{error}</div>}
    <div className="table-wrap"><table><thead><tr><th>Date</th><th>Booking</th><th>Member</th><th>DOL Plot</th><th>Settlement</th><th>Refund</th><th>Status</th><th>Review</th></tr></thead><tbody>{rows.map((r,i)=><tr key={i}><td>{String(r.cancellationDate||'').slice(0,10)}</td><td className="code">{String(r.bookingNo||'—')}</td><td>{String(r.memberName||'—')}<small>{String(r.mbrNo||'')}</small></td><td>{String(r.plotNo||'—')}<small>{String(r.blockName||'')}</small></td><td>{String(r.status).toUpperCase()==='APPROVED'?formatMoney(Number(r.deductionAmount||0)):'Not calculated'}</td><td>{String(r.status).toUpperCase()==='APPROVED'?formatMoney(Number(r.refundAmount||0)):'Not calculated'}</td><td><span className="badge">{String(r.status||'—')}</span></td><td><button className="secondary compact" onClick={()=>openDetail(Number(r.id))}>Details</button></td></tr>)}</tbody></table></div>
    {selected&&<section className="project-description"><div className="panel-head"><div><p className="eyebrow">CANCELLATION #{String(selected.id)}</p><h3>{String(selected.status)} · {String(selected.source_plot_no||selected.sourcePlotIdentity||'DOL identity unavailable')}</h3><p className="muted">Booking {String(selected.booking_id||'—')} · DOL identity is retained in history.</p></div><button className="secondary compact" onClick={()=>setSelected(null)}>Close details</button></div>
      <p><b>Posted amount:</b> {formatMoney(Number(financial.postedAmount||0))} <span className="muted">· Posted applications net of approved Sales merge transfers; pending/unposted receipts excluded.</span></p>
      <h4>Posted payment applications</h4><div className="table-wrap"><table><thead><tr><th>Receipt</th><th>Payment</th><th>Application</th><th>Receivable</th><th>Amount</th></tr></thead><tbody>{apps.map(a=><tr key={String(a.applicationId)}><td>{String(a.receiptNo||'—')}</td><td>{String(a.paymentId)}</td><td>{String(a.applicationId)}</td><td>{String(a.receivableId)}</td><td>{formatMoney(Number(a.appliedAmount||0))}</td></tr>)}</tbody></table></div>
      {(transfersIn.length>0||transfersOut.length>0)&&<><h4>Approved Sales merge transfers</h4><div className="table-wrap"><table><thead><tr><th>Transfer</th><th>Direction</th><th>Related DOL</th><th>Original application</th><th>Amount</th></tr></thead><tbody>{transfersIn.map(t=><tr key={`in-${String(t.transferId)}-${String(t.applicationId)}`}><td>{String(t.transferId)}</td><td>Received</td><td>{String(t.sourcePlotNo)}</td><td>{String(t.applicationId)}</td><td>{formatMoney(Number(t.amount||0))}</td></tr>)}{transfersOut.map(t=><tr key={`out-${String(t.transferId)}-${String(t.applicationId)}`}><td>{String(t.transferId)}</td><td>Transferred out</td><td>{String(t.targetPlotNo)}</td><td>{String(t.applicationId)}</td><td>{formatMoney(Number(t.amount||0))}</td></tr>)}</tbody></table></div></>}
      <h4>Receivables</h4><div className="table-wrap"><table><thead><tr><th>Receivable</th><th>Charge</th><th>Original</th><th>Paid</th><th>Outstanding</th></tr></thead><tbody>{receivables.map(r=><tr key={String(r.receivableId)}><td>{String(r.receivableId)}</td><td>{String(r.chargeName||'—')}</td><td>{formatMoney(Number(r.originalAmount||0))}</td><td>{formatMoney(Number(r.paidAmount||0))}</td><td>{formatMoney(Number(r.outstanding||0))}</td></tr>)}</tbody></table></div>
      {String(selected.status).toUpperCase()==='PENDING'&&canReview&&<><h4>Approved existing debit adjustments (optional deductions)</h4>{eligible.map(a=><label className="check-row" key={String(a.adjustmentId)}><input type="checkbox" checked={deductionIds.includes(Number(a.adjustmentId))} onChange={e=>setDeductionIds(e.target.checked?[...deductionIds,Number(a.adjustmentId)]:deductionIds.filter(x=>x!==Number(a.adjustmentId)))}/>{String(a.description)} · {formatMoney(Number(a.amount||0))}</label>)}<p><b>Indicative refundable amount at current review:</b> {formatMoney(refund)}. Recalculated transactionally when approved.</p><label>Decision remarks<textarea value={decision} onChange={e=>setDecision(e.target.value)} maxLength={1000}/></label><div className="modal-actions"><button className="secondary" disabled={saving||!decision.trim()} onClick={()=>decide('reject')}>Reject</button><button className="primary" disabled={saving||!decision.trim()} onClick={()=>decide('approve')}>Approve Cancellation</button></div></>}
      {refundCase.status==='PENDING_DISBURSEMENT'&&canReview&&<div className="form-grid"><label>Refund payout reference<input required maxLength={120} value={payoutReference} onChange={e=>setPayoutReference(e.target.value)}/></label><button className="primary" disabled={saving||!payoutReference.trim()} onClick={disburse}>Record Disbursement</button></div>}
      {Object.keys(refundCase).length>0&&<p><b>Refund:</b> {formatMoney(Number(refundCase.refundAmount||0))} · {String(refundCase.status)} {refundCase.payoutReference?`· Ref ${String(refundCase.payoutReference)}`:''}</p>}
      <h4>Event history</h4><ol>{events.map((event,i)=><li key={i}>{String(event.eventType)} · {String(event.fromStatus||'—')} → {String(event.toStatus||'—')} · {String(event.createdAt)} {event.remarks?`· ${String(event.remarks)}`:''}</li>)}</ol>
    </section>}
    {open&&<div className="modal-backdrop"><form className="modal" onSubmit={save}><div className="modal-head"><div><p className="eyebrow">CANCELLATION</p><h2>Request Booking Cancellation</h2><p className="muted">This is a PENDING request. Amounts are indicative until authorized review; the booking and plot remain committed.</p></div><button type="button" className="icon" onClick={()=>setOpen(false)}><X/></button></div><div className="form-grid"><label className="wide">Booking<select required value={form.bookingId} onChange={e=>setForm({...form,bookingId:e.target.value})}><option value="">Select allocated booking</option>{bookings.map(b=><option key={b.id} value={b.id}>{b.bookingNo} — {b.memberName} — {b.plotNo}</option>)}</select></label><label>Cancellation Date<input required type="date" value={form.cancellationDate} onChange={e=>setForm({...form,cancellationDate:e.target.value})}/></label><label>Reason<input value={form.reason} onChange={e=>setForm({...form,reason:e.target.value})}/></label><label className="wide">Remarks<input value={form.remarks} onChange={e=>setForm({...form,remarks:e.target.value})}/></label></div><div className="modal-actions"><button type="button" className="secondary" onClick={()=>setOpen(false)}>Cancel</button><button className="primary" disabled={saving}>{saving?'Submitting…':'Submit for Review'}</button></div></form></div>}</div>;
}
function SalesMerges(){
  const [rows,setRows]=useState<Record<string,unknown>[]>([]);const [plots,setPlots]=useState<Record<string,unknown>[]>([]);
  const [selected,setSelected]=useState<Record<string,unknown>|null>(null);const [open,setOpen]=useState(false);
  const [error,setError]=useState('');const [saving,setSaving]=useState(false);const [decision,setDecision]=useState('');
  const [mapping,setMapping]=useState<Record<string,string>>({});
  const [form,setForm]=useState({sourcePlotId:'',targetPlotId:'',mergeDate:new Date().toISOString().slice(0,10),notes:''});
  const canReview=canPerformAction('Plots & Sales','APPROVE');
  const load=()=>Promise.all([api<Record<string,unknown>[]>('/sales/merges'),api<Record<string,unknown>[]>('/sales/merge-candidates')]).then(([r,p])=>{setRows(r);setPlots(p)}).catch(e=>setError(e.message));
  useEffect(()=>{load()},[]);
  async function openDetail(id:number){setError('');try{setSelected(await api<Record<string,unknown>>(`/sales/merges/${id}`));setDecision('');setMapping({})}catch(e){setError(e instanceof Error?e.message:'Unable to load merge details.')}}
  async function save(e:React.FormEvent){e.preventDefault();setSaving(true);setError('');try{const result=await api<{mergeId:number}>('/sales/merges',{method:'POST',body:JSON.stringify({...form,sourcePlotId:Number(form.sourcePlotId),targetPlotId:Number(form.targetPlotId)})});setOpen(false);await load();await openDetail(result.mergeId)}catch(e){setError(e instanceof Error?e.message:'Unable to submit merge request.')}finally{setSaving(false)}}
  async function decide(action:'approve'|'reject'){if(!selected)return;setSaving(true);setError('');try{const source=((selected.financialPosition||{}) as Record<string,unknown>).source as Record<string,unknown>|undefined;const apps=Array.isArray(source?.postedApplications)?source!.postedApplications as Record<string,unknown>[]:[];const transferMappings=apps.map(a=>({applicationId:Number(a.applicationId),targetReceivableId:Number(mapping[String(a.applicationId)])}));await api(`/sales/merges/${String(selected.id)}/${action}`,{method:'POST',body:JSON.stringify(action==='approve'?{transferMappings,remarks:decision}:{remarks:decision})});await load();await openDetail(Number(selected.id))}catch(e){setError(e instanceof Error?e.message:'Unable to update merge request.')}finally{setSaving(false)}}
  const optionLabel=(p:Record<string,unknown>)=>`${String(p.plotNo||'')} — ${String(p.bookingNo||'')} — ${String(p.memberName||'')} (${String(p.registrationNo||'')})`;
  const position=(selected?.financialPosition||{}) as Record<string,unknown>;
  const source=(position.source||{}) as Record<string,unknown>;const target=(position.target||{}) as Record<string,unknown>;
  const apps=Array.isArray(source.postedApplications)?source.postedApplications as Record<string,unknown>[]:[];
  const targetReceivables=Array.isArray(target.receivables)?target.receivables as Record<string,unknown>[]:[];
  const sourceReservations=Array.isArray(source.activeReservations)?source.activeReservations as Record<string,unknown>[]:[];
  const targetReservations=Array.isArray(target.activeReservations)?target.activeReservations as Record<string,unknown>[]:[];
  const events=Array.isArray(selected?.events)?selected!.events as Record<string,unknown>[]:[];
  const canApprove=apps.every(a=>Boolean(mapping[String(a.applicationId)]))&&sourceReservations.length===0&&targetReservations.length===0;
  return <div className="panel"><div className="panel-head"><div><p className="eyebrow">PLOT MERGES</p><h3>Plot Merge Requests</h3><p className="muted">DOL identities remain permanent; approval transfers eligible posted applications explicitly.</p></div><button className="primary" onClick={()=>setOpen(true)}><Plus size={15}/> New Merge Request</button></div>
    {error&&<div className="error page-error">{error}</div>}<div className="table-wrap"><table><thead><tr><th>Date</th><th>Source DOL</th><th>Target DOL</th><th>Status</th><th>Approved By</th><th>Review</th></tr></thead><tbody>{rows.map((r,i)=><tr key={i}><td>{String(r.mergeDate||'').slice(0,10)}</td><td className="code">{String(r.sourcePlotNo||'—')}</td><td className="code">{String(r.targetPlotNo||'—')}</td><td><span className="badge">{String(r.status||'—')}</span></td><td>{String(r.approvedBy||'—')}</td><td><button className="secondary compact" onClick={()=>openDetail(Number(r.id))}>Details</button></td></tr>)}</tbody></table></div>
    {selected&&<section className="project-description"><div className="panel-head"><div><p className="eyebrow">MERGE #{String(selected.id)}</p><h3>{String(selected.status)} · {String(selected.source_plot_no||selected.sourcePlotIdentity||'')} → {String(selected.target_plot_no||selected.targetPlotIdentity||'')}</h3><p className="muted">Source and target plot IDs and DOL numbers remain in the request history.</p></div><button className="secondary compact" onClick={()=>setSelected(null)}>Close details</button></div>
      <h4>Financial review · posted applications only</h4>{apps.length===0?<p>No posted payment applications are available for transfer.</p>:apps.map(a=>{const choices=targetReceivables.filter(r=>Number(r.chargeTypeId)===Number(a.chargeTypeId));return <label className="wide" key={String(a.applicationId)}>Receipt {String(a.receiptNo)} · application {String(a.applicationId)} · {formatMoney(Number(a.appliedAmount||0))}<select value={mapping[String(a.applicationId)]||''} onChange={e=>setMapping({...mapping,[String(a.applicationId)]:e.target.value})}><option value="">Map to target receivable</option>{choices.map(r=><option key={String(r.receivableId)} value={String(r.receivableId)}>Receivable {String(r.receivableId)} · {String(r.chargeName||r.chargeTypeId)} · outstanding {formatMoney(Number(r.outstanding||0))}</option>)}</select></label>})}
      {(sourceReservations.length+targetReservations.length)>0&&<div className="error">Unposted Finance reservations block approval. Resolve them through Finance first.</div>}
      <p>Source/target bookings and inventory remain unchanged until approval completes.</p>
      {String(selected.status).toUpperCase()==='PENDING'&&canReview&&<><label>Decision remarks<textarea value={decision} onChange={e=>setDecision(e.target.value)} maxLength={1000}/></label><div className="modal-actions"><button className="secondary" disabled={saving||!decision.trim()} onClick={()=>decide('reject')}>Reject</button><button className="primary" disabled={saving||!decision.trim()||!canApprove} onClick={()=>decide('approve')}>Approve Merge</button></div></>}
      <h4>Finalized transfer record</h4>{(Array.isArray(selected.transfers)?selected.transfers as Record<string,unknown>[]:[]).map((x,i)=><p key={i}>Transfer {String(x.transferId)} · {formatMoney(Number(x.amount||x.totalAmount||0))} · receipt {String(x.paymentId||'—')} · allocation {String(x.allocationId||'—')} · application {String(x.applicationId||'—')}</p>)}
      <h4>Event history</h4><ol>{events.map((event,i)=><li key={i}>{String(event.eventType)} · {String(event.fromStatus||'—')} → {String(event.toStatus||'—')} · {String(event.createdAt)} {event.remarks?`· ${String(event.remarks)}`:''}</li>)}</ol>
    </section>}
    {open&&<div className="modal-backdrop"><form className="modal" onSubmit={save}><div className="modal-head"><div><p className="eyebrow">PLOT MERGE</p><h2>Submit Merge Request</h2><p className="muted">Submission creates a PENDING request only. Deposits, bookings and inventory are unchanged until authorized approval.</p></div><button type="button" className="icon" onClick={()=>setOpen(false)}><X/></button></div><div className="form-grid"><label>Source Plot / booking<select required value={form.sourcePlotId} onChange={e=>setForm({...form,sourcePlotId:e.target.value})}><option value="">Select source</option>{plots.map(p=><option key={String(p.plotId)} value={String(p.plotId)}>{optionLabel(p)}</option>)}</select></label><label>Target Plot / booking<select required value={form.targetPlotId} onChange={e=>setForm({...form,targetPlotId:e.target.value})}><option value="">Select target</option>{plots.filter(p=>String(p.plotId)!==form.sourcePlotId).map(p=><option key={String(p.plotId)} value={String(p.plotId)}>{optionLabel(p)}</option>)}</select></label><label>Merge Date<input required type="date" value={form.mergeDate} onChange={e=>setForm({...form,mergeDate:e.target.value})}/></label><label className="wide">Notes<input value={form.notes} onChange={e=>setForm({...form,notes:e.target.value})}/></label></div><div className="modal-actions"><button type="button" className="secondary" onClick={()=>setOpen(false)}>Close</button><button className="primary" disabled={saving||!plots.length}>{saving?'Submitting…':'Submit for Review'}</button></div></form></div>}</div>;
}
function Plots() { return <SalesWorkspace />; }

async function uploadFile<T>(path: string, file: File): Promise<T> {
  const token = sessionStorage.getItem('webv9_token');
  const formData = new FormData();
  formData.append('file', file);
  const response = await fetch(`${API}${path}`, {
    method: 'POST',
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: formData
  });
  if (!response.ok) {
    throw new Error((await response.json().catch(() => null))?.message || 'Failed to upload evidence file.');
  }
  return response.json();
}

const formatMoney = (val: number) => new Intl.NumberFormat('en-PK', { style: 'currency', currency: 'PKR', maximumFractionDigits: 0 }).format(val || 0);

type CustomerLedgerSale = Record<string, unknown> & {
  bookingId: number;
  totals: Record<string, unknown>;
  receivables: Record<string, unknown>[];
  receipts: Record<string, unknown>[];
  ledgerTransactions: Record<string, unknown>[];
};
type CustomerLedgerReportResponse = {
  criteria: { toDate: string };
  customer: Record<string, unknown>;
  sales: CustomerLedgerSale[];
  totals: Record<string, unknown>;
  warnings: string[];
  unallocatedReceipts: Record<string, unknown>[];
  unavailableBookingFields: string[];
};
type LedgerPartyMatch = { memberId: number; mbrNo: string; registrationNos?: string; formNos?: string; name: string; cnicNo: string };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isRecordArray(value: unknown): value is Record<string, unknown>[] {
  return Array.isArray(value) && value.every(isRecord);
}

function isCustomerLedgerReportResponse(value: unknown): value is CustomerLedgerReportResponse {
  if (!isRecord(value) || !isRecord(value.criteria) || typeof value.criteria.toDate !== 'string'
      || !isRecord(value.customer) || !isRecord(value.totals)
      || !Array.isArray(value.sales) || !isRecordArray(value.unallocatedReceipts)
      || !Array.isArray(value.warnings) || !value.warnings.every(item => typeof item === 'string')
      || !Array.isArray(value.unavailableBookingFields)) return false;
  return value.sales.every(sale => isRecord(sale) && typeof sale.bookingId === 'number'
    && isRecord(sale.totals) && isRecordArray(sale.receivables)
    && isRecordArray(sale.receipts)
    && sale.receipts.every(receipt => !Array.isArray(receipt.allocationDetails) || isRecordArray(receipt.allocationDetails))
    && isRecordArray(sale.ledgerTransactions));
}

type CustomerLedgerReportErrorBoundaryState = { error: string | null };
class CustomerLedgerReportErrorBoundary extends React.Component<
  { children: React.ReactNode },
  CustomerLedgerReportErrorBoundaryState
> {
  state: CustomerLedgerReportErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): CustomerLedgerReportErrorBoundaryState {
    return { error: error.message };
  }

  componentDidCatch(error: Error) {
    console.error('Customer Ledger report rendering failed.', error);
  }

  render() {
    if (this.state.error) return <CustomerLedgerReportStatus error={`The statement could not be rendered: ${this.state.error}`} />;
    return this.props.children;
  }
}

function CustomerLedgerReportStatus({ message, error }: { message?: string; error?: string }) {
  return <section className="customer-ledger-report" role={error ? 'alert' : 'status'}>
    <div className={error ? 'error page-error' : 'empty'}>{error || message}</div>
  </section>;
}

function CustomerLedgerWorkspace() {
  const [partySearchField, setPartySearchField] = useState('MBR_NO');
  const [partySearchTerm, setPartySearchTerm] = useState('');
  const [partyResults, setPartyResults] = useState<LedgerPartyMatch[]>([]);
  const [selectedParty, setSelectedParty] = useState<LedgerPartyMatch | null>(null);
  const [selectedMemberId, setSelectedMemberId] = useState<string>('');
  const [bookings, setBookings] = useState<Record<string, unknown>[]>([]);
  const [selectedBookingId, setSelectedBookingId] = useState('');
  const [destination, setDestination] = useState('NEW_TAB');
  const [reportType, setReportType] = useState('REGULAR');
  const [options, setOptions] = useState<Record<string, boolean>>({});
  const [criteriaError, setCriteriaError] = useState('');
  const [report, setReport] = useState<CustomerLedgerReportResponse | null>(null);
  const [reportLoading, setReportLoading] = useState(false);
  const [reportError, setReportError] = useState('');
  const [reportWindow, setReportWindow] = useState<Window | null>(null);
  const [toDate, setToDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [partySearchLoading, setPartySearchLoading] = useState(false);
  const [partySearchError, setPartySearchError] = useState('');
  const [bookingsLoading, setBookingsLoading] = useState(false);
  const [bookingsError, setBookingsError] = useState('');

  useEffect(() => () => {
    if (reportWindow && !reportWindow.closed) reportWindow.close();
  }, [reportWindow]);

  useEffect(() => {
    let cancelled = false;
    setSelectedBookingId('');
    setBookingsError('');
    if (!selectedMemberId) { setBookings([]); setBookingsLoading(false); return () => { cancelled = true; }; }
    setBookings([]);
    setBookingsLoading(true);
    api<Record<string, unknown>[]>(`/finance/members/${selectedMemberId}/bookings`)
      .then(rows => { if (!cancelled) setBookings(rows); })
      .catch(err => { if (!cancelled) setBookingsError(err instanceof Error ? err.message : 'Unable to load this member’s bookings.'); })
      .finally(() => { if (!cancelled) setBookingsLoading(false); });
    return () => { cancelled = true; };
  }, [selectedMemberId]);

  const criteriaOptions = [
    ['completeTransactions', 'Complete Transactions'], ['onlyToDateTran', 'Only ToDate Tran'],
    ['clearedTranOnly', 'Cleared Tran Only'], ['onlyActiveSale', 'Only Active Sale'],
    ['hideLps', 'Hide LPS'], ['hidePartner', 'Hide Partner'], ['hideReceipt', 'Hide Receipt'],
    ['hideAddressMobile', 'Hide Addr&Mob']
  ] as const;

  async function searchParties() {
    setPartySearchError('');
    setPartyResults([]);
    setSelectedParty(null);
    setSelectedMemberId('');
    setSelectedBookingId('');
    setReport(null);
    if (!partySearchTerm.trim()) { setPartySearchError('Enter a value to search for a customer.'); return; }
    setPartySearchLoading(true);
    try {
      const query = new URLSearchParams({ field: partySearchField, q: partySearchTerm.trim() });
      const matches = await api<LedgerPartyMatch[]>(`/finance/members/search?${query.toString()}`);
      setPartyResults(matches);
      if (matches.length === 0) setPartySearchError('No customers match this search.');
    } catch (err) {
      setPartySearchError(err instanceof Error ? err.message : 'Unable to search WEBV9 customers.');
    } finally { setPartySearchLoading(false); }
  }

  function selectParty(memberId: string) {
    const match = partyResults.find(row => String(row.memberId) === memberId) || null;
    setSelectedParty(match);
    setSelectedMemberId(match ? String(match.memberId) : '');
    setSelectedBookingId('');
    setReport(null);
    setCriteriaError('');
  }

  async function executeCriteria(e: React.FormEvent) {
    e.preventDefault();
    setCriteriaError('');
    setReport(null);
    setReportError('');
    if (reportWindow && !reportWindow.closed) reportWindow.close();
    setReportWindow(null);
    if (partySearchLoading) { setCriteriaError('Wait for customer search to finish.'); return; }
    if (!selectedMemberId || !selectedParty) { setCriteriaError('Search for and select a customer/member from the results.'); return; }
    if (!toDate) { setCriteriaError('Select a To Date.'); return; }
    if (bookingsLoading) { setCriteriaError('Wait for this customer’s sales/bookings to finish loading.'); return; }
    if (bookingsError) { setCriteriaError('This customer’s sales/bookings could not be loaded. Select the customer again or refresh.'); return; }
    if (selectedBookingId && !bookings.some(row => String(row.id) === selectedBookingId)) {
      setCriteriaError('Select a valid sale/booking for this member.'); return;
    }
    const request = {
      memberId: Number(selectedMemberId), bookingId: selectedBookingId ? Number(selectedBookingId) : null,
      toDate, reportType, options
    };
    let targetWindow: Window | null = null;
    let reportRoot: Root | null = null;
    if (destination === 'NEW_TAB') {
      try {
        targetWindow = window.open('', '_blank');
        if (targetWindow) {
          targetWindow.document.open();
          targetWindow.document.write(`<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><base href="${window.location.origin}/"><title>Customer Account Statement</title></head><body><div id="ledger-report-root"></div></body></html>`);
          targetWindow.document.close();
          document.querySelectorAll('link[rel="stylesheet"], style').forEach(styleNode => {
            targetWindow?.document.head.appendChild(styleNode.cloneNode(true));
          });
          const rootElement = targetWindow.document.getElementById('ledger-report-root');
          if (!rootElement) throw new Error('The report tab could not initialize its display area.');
          reportRoot = createRoot(rootElement);
          reportRoot.render(<CustomerLedgerReportStatus message="Generating customer account statement…" />);
        }
      } catch (err) {
        const message = err instanceof Error ? err.message : 'The report tab could not be initialized.';
        if (targetWindow && !targetWindow.closed && !reportRoot) targetWindow.document.body.textContent = message;
        targetWindow = null;
        setReportError(message);
      }
    }
    setReportWindow(targetWindow);
    setReportLoading(true);
    try {
      const response = await api<unknown>('/finance/ledger/report', {
        method: 'POST', body: JSON.stringify({ memberId: request.memberId, bookingId: request.bookingId,
          toDate: request.toDate, reportType: request.reportType, options: request.options })
      });
      if (!isCustomerLedgerReportResponse(response)) {
        throw new Error('The server returned an incomplete Customer Ledger report.');
      }
      setReport(response);
      if (reportRoot && targetWindow && !targetWindow.closed) {
        try {
          reportRoot.render(
            <CustomerLedgerReportErrorBoundary>
              <CustomerLedgerReportView report={response} hideReceipts={Boolean(options.hideReceipt)} printTarget={targetWindow} />
            </CustomerLedgerReportErrorBoundary>
          );
          setReportError('');
        } catch (err) {
          const message = err instanceof Error ? err.message : 'The Customer Ledger report could not be displayed.';
          targetWindow.document.body.textContent = message;
          setReportError(message);
        }
      } else {
        setReportError('');
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Unable to generate the customer statement.';
      setReportError(message);
      if (reportRoot && targetWindow && !targetWindow.closed) {
        try {
          reportRoot.render(<CustomerLedgerReportStatus error={message} />);
        } catch {
          targetWindow.document.body.textContent = message;
        }
      }
    } finally { setReportLoading(false); }
  }

  return (
    <div className="panel finance-subpanel">
      <div className="panel-head">
        <div>
          <p className="eyebrow">FINANCE / CUSTOMER LEDGER</p>
          <h3>Customer Ledger / Account Statement</h3>
        </div>
      </div>

      <form className="ledger-criteria" onSubmit={executeCriteria}>
        <div className="ledger-criteria-title">Report Criteria - Customer Ledger</div>
        <div className="ledger-criteria-toolbar">
          <label>Destination<select value={destination} onChange={e => setDestination(e.target.value)}><option value="NEW_TAB">New Tab</option></select></label>
          <button className="primary" type="submit"><FileText size={15} /> Execute</button>
        </div>
        <div className="ledger-criteria-fields">
          <label>Select Location<select value="district-one" disabled><option value="district-one">District One Housing</option></select></label>
          <label>To Date<input type="date" required value={toDate} onChange={e => setToDate(e.target.value)} /></label>
          <div className="ledger-party-search">
            <label>Search by<select value={partySearchField} onChange={e => { setPartySearchField(e.target.value); setPartyResults([]); setSelectedParty(null); setSelectedMemberId(''); setSelectedBookingId(''); }}><option value="MBR_NO">MBR No.</option><option value="REGISTRATION_NO">Registration No.</option><option value="FORM_NO">Form No.</option><option value="NAME">Name</option><option value="CNIC_NO">CNIC</option></select></label>
            <label>Party search<input value={partySearchTerm} placeholder="Enter an identifier or customer name" onChange={e => { setPartySearchTerm(e.target.value); setPartyResults([]); setSelectedParty(null); setSelectedMemberId(''); setSelectedBookingId(''); }} onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); void searchParties(); } }} /></label>
            <button className="secondary" type="button" disabled={partySearchLoading} onClick={() => void searchParties()}>{partySearchLoading ? 'Searching…' : 'Search'}</button>
            <label className="ledger-party-results">Matching Customers<select size={Math.max(2, Math.min(6, partyResults.length || 2))} value={selectedMemberId} onChange={e => selectParty(e.target.value)} disabled={partySearchLoading || partyResults.length === 0}>
              {partyResults.length === 0 ? <option value="">{partySearchLoading ? 'Searching live WEBV9 data…' : 'Search to load matching customers'}</option> : <><option value="">Select a matching customer</option>{partyResults.map(match => <option key={match.memberId} value={String(match.memberId)}>{match.mbrNo} | Reg: {match.registrationNos || '—'} | Form: {match.formNos || '—'} | {match.name} | CNIC: {match.cnicNo || '—'}</option>)}</>}
            </select></label>
            {selectedParty && <div className="ledger-selected-party">Selected: <b>{selectedParty.mbrNo}</b> · {selectedParty.name} · Member ID {selectedParty.memberId}</div>}
            {partySearchError && <div className="error page-error">{partySearchError}</div>}
          </div>
          <label>Select Sale<select value={selectedBookingId} disabled={!selectedMemberId || bookingsLoading || Boolean(bookingsError) || bookings.length === 0} onChange={e => setSelectedBookingId(e.target.value)}><option value="">{bookingsLoading ? 'Loading sales/bookings…' : bookingsError ? 'Sales/bookings unavailable' : !selectedMemberId ? 'Select a party first' : bookings.length === 0 ? 'No sales/bookings found for this customer' : 'All Sales / Bookings'}</option>{bookings.map(row => <option key={String(row.id)} value={String(row.id)}>Sale/Booking ID {String(row.id)} — Reg {String(row.registrationNo || '—')} — Plot {String(row.plotNo || '—')} {String(row.blockName || '')} · {String(row.sizeMarla || '—')} marla · {String(row.status || '—')}</option>)}</select></label>
          <fieldset className="ledger-report-types"><legend>Report Type</legend><label><input type="radio" name="ledger-report-type" value="REGULAR" checked={reportType === 'REGULAR'} onChange={() => setReportType('REGULAR')} /> Regular Report</label><label><input type="radio" name="ledger-report-type" value="KNOCKED_WISE" checked={reportType === 'KNOCKED_WISE'} onChange={() => setReportType('KNOCKED_WISE')} /> Knocked Wise</label></fieldset>
        </div>
        <div className="ledger-options">{criteriaOptions.map(([key, label]) => <label key={key}><input type="checkbox" checked={Boolean(options[key])} onChange={e => setOptions(current => ({ ...current, [key]: e.target.checked }))} />{label}</label>)}</div>
        {criteriaError && <div className="error page-error">{criteriaError}</div>}
        {!criteriaError && bookingsError && <div className="error page-error">Sales/booking data could not be loaded: {bookingsError}</div>}
        {criteriaOptions.filter(([key]) => ['completeTransactions', 'clearedTranOnly', 'onlyActiveSale', 'hideLps', 'hidePartner'].includes(key) && Boolean(options[key])).length > 0 && <div className="ledger-option-note">Some selected legacy options are not applied because WEBV9 has no established data rule for them. See the report notes after generation.</div>}
      </form>
      {reportLoading && <div className="empty">Generating statement from posted WEBV9 finance records…</div>}
      {reportError && <div className="error page-error">{reportError}</div>}
      {report && (!reportWindow || reportWindow.closed) && <CustomerLedgerReportView report={report} hideReceipts={Boolean(options.hideReceipt)} />}
    </div>
  );
}

function CustomerLedgerReportView({ report, hideReceipts, printTarget }: { report: CustomerLedgerReportResponse; hideReceipts: boolean; printTarget?: Window | null }) {
  const customer = report.customer;
  const sales = report.sales || [];
  const totals = report.totals || {};
  const money = (value: unknown) => new Intl.NumberFormat('en-PK', { style: 'currency', currency: 'PKR', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Number(value) || 0);
  const text = (value: unknown) => value == null || value === '' ? '—' : String(value);
  const reportDate = new Date().toLocaleDateString('en-GB');
  return <section className="customer-ledger-report">
    <div className="ledger-report-actions"><span>Customer Account Statement</span><button className="secondary" onClick={() => printTarget ? printTarget.print() : window.print()}><FileText size={15} /> Print / Save PDF</button></div>
    <header className="ledger-report-header">
      <img src={districtOneLogo} alt="District One Housing" />
      <div className="ledger-report-identity"><h1>District One Lahore Housing Society</h1><p>Account Statement / Surcharge Report</p></div>
      <div className="ledger-report-hq"><b>Head Office</b><br/>District One Housing Sharif<br/>Medical City Road, Jati Umrah, Lahore.<br/>+92 325 1663663<br/>districtonehousing.pk<br/>Info@districtonehousing.pk</div>
    </header>
    <section className="ledger-customer-block">
      <h3>Member Information</h3>
      <table className="ledger-information-table"><tbody>
        <tr><th>Membership #</th><td>{text(customer.membershipNo)}</td><th>Registration #</th><td>{Array.isArray(customer.registrationNumbers) ? customer.registrationNumbers.map(String).join(', ') || '—' : '—'}</td></tr>
        <tr><th>Customer</th><td colSpan={3}>{text(customer.name)}</td></tr>
        {!customer.contactHidden && <>
          <tr><th>Address</th><td colSpan={3}>{[customer.address1, customer.address2, customer.city].filter(Boolean).map(String).join(', ') || '—'}</td></tr>
          <tr><th>Contact</th><td colSpan={3}>{[customer.contact1, customer.contact2, customer.landline1, customer.landline2].filter(Boolean).map(String).join(' · ') || '—'}</td></tr>
        </>}
      </tbody></table>
    </section>
    {sales.length === 0 ? <div className="empty">This customer has no sales/bookings in WEBV9.</div> : sales.map((sale, index) => {
      const saleTotals = sale.totals as Record<string, number>;
      const receivables = Array.isArray(sale.receivables) ? sale.receivables as Record<string, unknown>[] : [];
      const receipts = Array.isArray(sale.receipts) ? sale.receipts as Record<string, unknown>[] : [];
      const ledger = Array.isArray(sale.ledgerTransactions) ? sale.ledgerTransactions as Record<string, unknown>[] : [];
      const receiptByReceivable = new Map<string, { dates: Set<string>; receiptNumbers: Set<string> }>();
      receipts.forEach(receipt => {
        const details = Array.isArray(receipt.allocationDetails) ? receipt.allocationDetails as Record<string, unknown>[] : [];
        details.forEach(detail => {
          if (detail.receivableId == null || detail.receivableId === '') return;
          const key = String(detail.receivableId);
          const linked = receiptByReceivable.get(key) || { dates: new Set<string>(), receiptNumbers: new Set<string>() };
          if (receipt.receiptDate != null) linked.dates.add(String(receipt.receiptDate).slice(0, 10));
          if (receipt.receiptNo != null) linked.receiptNumbers.add(String(receipt.receiptNo));
          receiptByReceivable.set(key, linked);
        });
      });
      const receivablesByHead = new Map<string, Record<string, unknown>[]>();
      receivables.forEach(row => {
        const head = String(row.head || row.description || 'Charge head not available');
        receivablesByHead.set(head, [...(receivablesByHead.get(head) || []), row]);
      });
      let receiptSerial = 0;
      const receiptRows = receipts.flatMap((receipt, receiptIndex) => {
        const allocationDetails: (Record<string, unknown> | null)[] =
          Array.isArray(receipt.allocationDetails) && (receipt.allocationDetails as Record<string, unknown>[]).length > 0
            ? receipt.allocationDetails as Record<string, unknown>[] : [null];
        const serial = ++receiptSerial;
        return allocationDetails.map((detail, detailIndex) => <tr key={`${receipt.paymentId}-${receiptIndex}-${detailIndex}`}>
          {detailIndex === 0 && <>
            <td rowSpan={allocationDetails.length}>{serial}</td>
            <td rowSpan={allocationDetails.length}>{text(receipt.receiptNo)}</td>
            <td rowSpan={allocationDetails.length}>{text(receipt.receiptType)}</td>
            <td rowSpan={allocationDetails.length}>{text(receipt.paymentReference)}</td>
            <td rowSpan={allocationDetails.length}>{text(receipt.salesClassification)}</td>
            <td rowSpan={allocationDetails.length}>{text(receipt.bankInstrumentType)}</td>
          </>}
          <td>{text([
            detail?.head || detail?.description || receipt.head,
            detail?.installmentNo == null ? '' : `Installment ${detail.installmentNo}`,
            detail?.salesTransferId == null ? '' : `Re-associated by Sales merge ${detail.salesTransferId} to booking ${detail.transferredToBookingId} · ${detail.transferredToPlotNo}`
          ].filter(Boolean).join(' / '))}</td>
          {detailIndex === 0 && <>
            <td rowSpan={allocationDetails.length}>{text(receipt.instrumentNo)}</td>
            <td rowSpan={allocationDetails.length}>{text(receipt.receiptDate)}</td>
            <td className="ledger-number" rowSpan={allocationDetails.length}>{money(receipt.headerAmount)}</td>
          </>}
          <td className="ledger-number">{money(detail?.amount ?? receipt.allocatedAmount)}</td>
          {detailIndex === 0 && <td rowSpan={allocationDetails.length}>Booking {text(sale.bookingId)} · {text(sale.plotNo)}</td>}
        </tr>);
      });
      return <article className="ledger-sale-section" key={String(sale.bookingId)}>
        <div className="ledger-sale-heading"><div><p className="eyebrow">SALE / PLOT {index + 1}</p><h3>{text(sale.bookingFormNo)} <small>Booking ID {text(sale.bookingId)}</small></h3></div><span className="badge">{text(sale.status)}</span></div>
        <h4>Booking Information</h4>
        <table className="ledger-information-table ledger-sale-information"><tbody>
          <tr><th>Booking / Form #</th><td>{text(sale.bookingFormNo)}</td><th>Cost of Land</th><td>{sale.costOfLand == null ? 'Not available in WEBV9' : money(sale.costOfLand)}</td></tr>
          <tr><th>Block</th><td>{text(sale.blockName)}</td><th>Factor</th><td>{sale.factor == null ? 'Not available in WEBV9' : text(sale.factor)}</td></tr>
          <tr><th>Size</th><td>{sale.sizeMarla == null ? '—' : `${text(sale.sizeMarla)} Marla`}</td><th>Status</th><td>{text(sale.status)}</td></tr>
          <tr><th>Plot</th><td>{text(sale.plotNo)}</td><th>Type / Category</th><td>{[sale.type, sale.category].filter(Boolean).map(String).join(' / ') || '—'}</td></tr>
        </tbody></table>
        <h4>Transaction Detail — Charges</h4>
        {[...receivablesByHead.entries()].length === 0 ? <div className="ledger-empty-inline">No receivables due by the selected To Date.</div> : [...receivablesByHead.entries()].map(([head, rows]) => <div className="ledger-charge-group" key={head}>
          <div className="ledger-charge-heading">{head}</div>
          <div className="table-wrap"><table className="ledger-data-table">
            <thead><tr><th>Head</th><th>Amount</th><th>Due Date</th><th>Paid</th><th>Paid On</th><th>DS / Receipt No.</th><th>Outstanding</th><th>Surcharge</th></tr></thead>
            <tbody>{rows.map((row, rowIndex) => {
              const linked = receiptByReceivable.get(String(row.receivableId));
              return <tr key={String(row.receivableId || rowIndex)}>
                <td>{text(row.head || row.description)}</td><td className="ledger-number">{money(row.amount)}</td><td>{text(row.dueDate)}</td><td className="ledger-number">{money(row.paidAmount)}</td>
                <td>{linked ? [...linked.dates].join(', ') || '—' : '—'}</td><td>{linked ? [...linked.receiptNumbers].join(', ') || '—' : '—'}</td>
                <td className="ledger-number">{money(row.outstanding)}</td><td className="ledger-number">{money(row.surchargeAmount)}</td>
              </tr>;
            })}</tbody>
          </table></div>
        </div>)}
        {!hideReceipts && <>
          <h4>Receipt Detail</h4>
          <div className="table-wrap"><table className="ledger-data-table ledger-receipt-table">
          <thead><tr><th>Sr. #</th><th>Rcpt. #</th><th>Receipt Type</th><th>Inst. Type</th><th>Instrument. #</th><th>Receipt Date</th><th className="ledger-number">Receipt Amount</th></tr></thead>
          <tbody>{receipts.length === 0 ? <tr><td colSpan={7} className="ledger-empty-inline">No posted receipts allocated to this sale by the selected To Date.</td></tr>
            : receipts.map((receipt, ri) => <tr key={`${receipt.paymentId}-${ri}`}>
              <td>{ri + 1}</td>
              <td>{text(receipt.receiptNo)}</td>
              <td>{text(receipt.receiptType)}</td>
              <td>{text(receipt.bankInstrumentType) || text(receipt.receiptType)}</td>
              <td>{text(receipt.instrumentNo) || 'N/A'}</td>
              <td>{text(receipt.receiptDate)}</td>
              <td className="ledger-number">{money(receipt.headerAmount)}</td>
            </tr>)
          }</tbody>
          </table></div>
        </>}
        <h4>Transaction Detail — Posted Ledger</h4>
        <div className="table-wrap"><table className="ledger-data-table">
          <thead><tr><th>Transaction Date</th><th>Reference #</th><th>Type</th><th>Description</th><th className="ledger-number">Debit</th><th className="ledger-number">Credit</th></tr></thead>
          <tbody>{ledger.length === 0 ? <tr><td colSpan={6} className="ledger-empty-inline">No posted ledger entries by the selected To Date.</td></tr> : ledger.map((row, ri) => <tr key={`${row.id}-${ri}`}>
            <td>{text(row.transactionDate)}</td><td>{text(row.referenceNo)}</td><td>{text(row.type)}</td><td>{text(row.description)}</td><td className="ledger-number">{money(row.debit)}</td><td className="ledger-number">{money(row.credit)}</td>
          </tr>)}</tbody>
        </table></div>
        <h4>Sale Total / Sale Summary</h4>
        <div className="table-wrap"><table className="ledger-summary-table">
          <thead><tr><th>Total Charges</th><th>Total Paid</th><th>Total Adjustments</th><th>Total Surcharge</th><th>Total Outstanding</th></tr></thead>
          <tbody><tr><td>{money(saleTotals?.charges)}</td><td>{money(saleTotals?.paid)}</td><td>{money(saleTotals?.adjustments)}</td><td>{money(saleTotals?.surcharge)}</td><td>{money(saleTotals?.outstanding)}</td></tr></tbody>
        </table></div>
      </article>;
    })}
    {!hideReceipts && report.unallocatedReceipts.length > 0 && <section className="ledger-unallocated"><h3>Customer-level Receipts — No Sale Allocation</h3><p>These posted receipts have no allocation to a WEBV9 sale and are not included in sale-level totals.</p><div className="table-wrap"><table className="ledger-data-table"><thead><tr><th>Sr. #</th><th>Receipt #</th><th>Receipt Type</th><th>Payment Reference</th><th>Sales Classification</th><th>Bank Instrument</th><th>Bank Document No.</th><th>Receipt Date</th><th>Receipt Amount</th><th>Sale / Booking Association</th></tr></thead><tbody>{report.unallocatedReceipts.map((r, i) => <tr key={`${r.paymentId}-${i}`}><td>{i + 1}</td><td>{text(r.receiptNo)}</td><td>{text(r.receiptType)}</td><td>{text(r.paymentReference)}</td><td>{text(r.salesClassification)}</td><td>{text(r.bankInstrumentType)}</td><td>{text(r.instrumentNo)}</td><td>{text(r.receiptDate)}</td><td className="ledger-number">{money(r.receiptAmount)}</td><td>Not allocated to a sale in WEBV9</td></tr>)}</tbody></table></div></section>}
    <section className="ledger-overall-summary"><h3>Customer Account Summary</h3><div className="table-wrap"><table className="ledger-summary-table">
      <thead><tr><th>Total Charges</th><th>Total Paid</th><th>Total Adjustments</th><th>Total Surcharge</th><th>Total Outstanding</th></tr></thead>
      <tbody><tr><td>{money(totals.charges)}</td><td>{money(totals.paid)}</td><td>{money(totals.adjustments)}</td><td>{money(totals.surcharge)}</td><td>{money(totals.outstanding)}</td></tr></tbody>
    </table></div></section>
    {report.warnings.length > 0 && <details className="ledger-report-notes"><summary>Data and Option Notes ({report.warnings.length})</summary><ul>{report.warnings.map((warning, i) => <li key={i}>{warning}</li>)}</ul></details>}
    <footer className="ledger-report-footer"><span>District One Lahore Housing Society · Customer Account Statement</span><span>Printed {reportDate} · Generated from WEBV9 records</span></footer>
  </section>;
}

function ReceivablesWorkspace() {
  const [rows, setRows] = useState<Record<string, unknown>[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = () => {
    setLoading(true);
    setError('');
    api<Record<string, unknown>[]>('/finance/receivables')
      .then(setRows)
      .catch(e => setError(e instanceof Error ? e.message : 'Failed to load receivables.'))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  return (
    <div className="panel finance-subpanel">
      <div className="panel-head">
        <div>
          <p className="eyebrow">FINANCE / RECEIVABLES & DUES</p>
          <h3>Outstanding Charges & Member Dues</h3>
        </div>
        <button className="secondary" onClick={load}><RefreshCw size={15} /> Refresh</button>
      </div>

      {error && <div className="error page-error" style={{ marginTop: '15px' }}>{error}</div>}

      {loading ? (
        <div className="empty">Loading receivables & outstanding dues...</div>
      ) : rows.length === 0 ? (
        <div className="empty">No outstanding receivables found.</div>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Member</th>
                <th>Due Date</th>
                <th>Original Amount</th>
                <th>Paid Amount</th>
                <th>Surcharge</th>
                <th>Adjustments</th>
                <th>Outstanding Balance</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={i}>
                  <td>
                    <b>{String(r.name || '')}</b>
                    <small>{String(r.mbrNo || '')}</small>
                  </td>
                  <td>{r.dueDate ? String(r.dueDate).slice(0, 10) : '—'}</td>
                  <td>{formatMoney(Number(r.originalAmount || 0))}</td>
                  <td>{formatMoney(Number(r.paidAmount || 0))}</td>
                  <td>{formatMoney(Number(r.surcharge || 0))}</td>
                  <td>{formatMoney(Number(r.adjustments || 0))}</td>
                  <td>
                    <b style={{ color: Number(r.outstanding || 0) > 0 ? '#8b2d2d' : '#176c4d' }}>
                      {formatMoney(Number(r.outstanding || 0))}
                    </b>
                  </td>
                  <td>
                    <span className={String(r.status) === 'PAID' ? 'status-badge success' : String(r.status) === 'PARTIAL' ? 'status-badge warning' : 'status-badge danger'}>
                      {String(r.status || 'DUE')}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

type PaymentRecord = {
  id: number;
  receiptNo: string;
  paymentDate: string;
  amount: number;
  method: string;
  paymentChannel?: string;
  paymentReference?: string;
  registrationNo?: string;
  location?: string;
  postedToGl?: string;
  approvalStatus?: string;
  salesClassification?: string;
  bankInstrumentType?: string;
  status: string;
  referenceNo?: string;
  receivingAccount?: string;
  depositDate?: string;
  remarks?: string;
  rejectionReason?: string;
  memberId: number;
  mbrNo: string;
  memberName: string;
  chargeTypeId?: number;
  chargeName?: string;
  chargeCode?: string;
  bankId?: number;
  bankName?: string;
  cashSiteId?: number;
  cashSiteName?: string;
  enteredBy?: string;
  verifiedBy?: string;
  verifiedSourceAmount?: number;
  verificationReference?: string;
  verificationRemarks?: string;
  verifiedAt?: string;
  evidenceId?: number;
  evidenceName?: string;
  allocationCount?: number;
};

type AllocationDueOption = {
  receivableId: number;
  memberId: number;
  registrationId: number;
  registrationNo: string;
  bookingId: number;
  bookingNo: string;
  plotId: number;
  plotNo: string;
  blockName: string;
  chargeTypeId: number;
  chargeCode: string;
  chargeName: string;
  dueDate: string;
  originalAmount: number;
  surcharge: number;
  adjustments: number;
  paidAmount: number;
  reservedAmount: number;
  availableDue: number;
  description?: string;
};
type FinanceRegistrationOption = {
  regNo: string;
  formNo?: string;
  category?: string;
  registrationStatus?: string;
  memberId: number;
  mbrNo: string;
  memberName: string;
};
type FinanceMemberRegistrationSearch = { memberId: number; mbrNo: string; registrationNos: string; name: string };
type ReceiptAllocationFormLine = { receivableId: string; amount: string };
type PaymentAllocationRecord = {
  id: number;
  lineNo: number;
  registrationNo: string;
  bookingNo: string;
  plotNo: string;
  blockName: string;
  chargeCode: string;
  chargeName: string;
  receivableId: number | null;
  receivableDescription?: string | null;
  dueDate?: string | null;
  installmentNo?: number | null;
  allocatedAmount: number;
  remarks?: string | null;
};

function PaymentAllocationSummary({ rows, loading, error }: {
  rows: PaymentAllocationRecord[];
  loading: boolean;
  error: string;
}) {
  return <section className="payment-allocation-summary">
    <div className="panel-head"><div><p className="eyebrow">PERSISTED PAYMENT ALLOCATIONS</p><h3>Charge-head application scope</h3></div></div>
    {error ? <div className="error">{error}</div> : loading ? <div className="empty">Loading allocation lines…</div>
      : rows.length === 0 ? <div className="empty">No persisted allocation lines are recorded for this receipt.</div>
        : <div className="table-wrap"><table><thead><tr><th>Registration / Booking</th><th>Plot</th><th>Charge Head</th><th>Receivable / Due</th><th>Allocated (PKR)</th></tr></thead>
          <tbody>{rows.map(row => <tr key={row.id}>
            <td>{row.registrationNo}<small>{row.bookingNo}</small></td>
            <td>{row.plotNo}<small>{row.blockName}</small></td>
            <td>{row.chargeName}<small>{row.chargeCode}</small></td>
            <td>{row.receivableDescription || (row.receivableId == null ? 'No receivable link' : `Receivable #${row.receivableId}`)}
              <small>{row.dueDate ? String(row.dueDate).slice(0, 10) : '—'}{row.installmentNo == null ? '' : ` · Instalment ${row.installmentNo}`}</small></td>
            <td>{formatMoney(Number(row.allocatedAmount))}</td>
          </tr>)}</tbody></table></div>}
  </section>;
}

function PaymentsRecoveryWorkspace({ entryOnly = false, onBack, onNavigate }: { entryOnly?: boolean; onBack?: () => void; onNavigate?: (tab: string) => void }) {
  const [summary, setSummary] = useState<Record<string, unknown> | null>(null);
  const [payments, setPayments] = useState<PaymentRecord[]>([]);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [search, setSearch] = useState('');
  const [receiptFilters, setReceiptFilters] = useState({ fromDate: '', toDate: '', receiptNo: '', registrationNo: '', partyName: '', projectName: '', location: '', paymentMethod: 'ALL', bankDocument: '', postedToGl: 'ALL', approvalStatus: 'ALL', receiptStatus: 'ALL' });
  const [receiptPage, setReceiptPage] = useState(0);
  const receiptPageSize = 50;
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [members, setMembers] = useState<Customer[]>([]);
  const [registrations, setRegistrations] = useState<FinanceRegistrationOption[]>([]);
  const [registrationSearch, setRegistrationSearch] = useState('');
  const [selectedRegistrationNo, setSelectedRegistrationNo] = useState('');
  const [registrationsLoading, setRegistrationsLoading] = useState(false);
  const [registrationError, setRegistrationError] = useState('');
  const [projectDataError, setProjectDataError] = useState('');
  const [salesBookings, setSalesBookings] = useState<SalesBooking[]>([]);
  const [projects, setProjects] = useState<ProjectRecord[]>([]);
  const [projectsLoading, setProjectsLoading] = useState(false);
  const [salesBookingsLoading, setSalesBookingsLoading] = useState(false);
  const [banks, setBanks] = useState<Record<string, unknown>[]>([]);
  const [cashSites, setCashSites] = useState<Record<string, unknown>[]>([]);
  const [memberBookings, setMemberBookings] = useState<Record<string, unknown>[]>([]);
  const [allocationDues, setAllocationDues] = useState<AllocationDueOption[]>([]);
  const [entryOptionsLoading, setEntryOptionsLoading] = useState(true);
  const [entryOptionsError, setEntryOptionsError] = useState('');
  const [bookingsLoading, setBookingsLoading] = useState(false);
  const [bookingsError, setBookingsError] = useState('');
  const [duesLoading, setDuesLoading] = useState(false);
  const [duesError, setDuesError] = useState('');
  const [paymentAllocations, setPaymentAllocations] = useState<PaymentAllocationRecord[]>([]);
  const [allocationsLoading, setAllocationsLoading] = useState(false);
  const [allocationError, setAllocationError] = useState('');

  const [recoveryModal, setModalRecovery] = useState(false);
  const [detailPayment, setDetailPayment] = useState<PaymentRecord | null>(null);
  const [rejectPaymentItem, setRejectPaymentItem] = useState<PaymentRecord | null>(null);
  const [rejectReason, setRejectReason] = useState('');
  const [rejecting, setRejecting] = useState(false);
  const [verificationPayment, setVerificationPayment] = useState<PaymentRecord | null>(null);
  const [verifiedSourceAmount, setVerifiedSourceAmount] = useState('');
  const [verifiedSourceReference, setVerifiedSourceReference] = useState('');
  const [verificationRemarks, setVerificationRemarks] = useState('');
  const [verifying, setVerifying] = useState(false);

  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');
  const [evidenceFile, setEvidenceFile] = useState<File | null>(null);
  const [receiptCreated, setReceiptCreated] = useState('');
  const recoverySubmitLock = useRef(false);
  const [primaryAllocationAmount, setPrimaryAllocationAmount] = useState('');
  const [additionalAllocations, setAdditionalAllocations] = useState<ReceiptAllocationFormLine[]>([]);

  const [form, setForm] = useState({
    memberId: '',
    registrationId: '',
    partyRoleType: '',
    projectName: '',
    plotSizeMarla: '',
    bookingId: '',
    chargeTypeId: '',
    receivableId: '',
    amount: '',
    paymentDate: new Date().toISOString().slice(0, 10),
    method: entryOnly ? 'CASH' : 'BANK_TRANSFER',
    paymentReference: '',
    salesClassification: '',
    bankInstrumentType: '',
    bankId: '',
    receivingAccount: '',
    referenceNo: '',
    depositDate: new Date().toISOString().slice(0, 10),
    cashSiteId: '',
    remarks: ''
  });

  const loadData = () => {
    setLoading(true);
    setError('');
    const params = new URLSearchParams();
    params.set('status', entryOnly ? receiptFilters.receiptStatus : statusFilter);
    params.set('page', String(receiptPage));
    params.set('pageSize', String(receiptPageSize));
    if (search.trim()) params.set('q', search.trim());
    if (entryOnly) Object.entries(receiptFilters).forEach(([key, value]) => {
      if (value && !['receiptStatus'].includes(key)) params.set(key, value);
    });
    Promise.all([
      api<Record<string, unknown>>('/finance/summary'),
      api<PaymentRecord[]>(`/finance/payments?${params.toString()}`)
    ]).then(([summaryData, paymentsData]) => {
      setSummary(summaryData);
      setPayments(paymentsData);
    }).catch(err => {
      setError(err instanceof Error ? err.message : 'Unable to load payment records.');
    }).finally(() => setLoading(false));
  };

  useEffect(() => { loadData(); }, [entryOnly, statusFilter, search, receiptFilters, receiptPage]);
  useEffect(() => {
    setModalRecovery(false);
    if (entryOnly) {
      setSelectedRegistrationNo('');
      setRegistrationSearch('');
      setAdditionalAllocations([]);
      setPrimaryAllocationAmount('');
      setForm(current => ({ ...current, registrationId: '', partyRoleType: '', projectName: '', plotSizeMarla: '', method: 'CASH', bankId: '', receivingAccount: '', referenceNo: '', cashSiteId: '' }));
    }
  }, [entryOnly]);

  function loadEntryOptions() {
    setEntryOptionsLoading(true);
    setEntryOptionsError('');
    Promise.all([
      api<Customer[]>('/customers'),
      api<Record<string, unknown>[]>('/finance/banks'),
      api<Record<string, unknown>[]>('/finance/cash-sites')
    ]).then(([memberRows, bankRows, cashRows]) => {
      setMembers(memberRows);
      setBanks(bankRows);
      setCashSites(cashRows);
      setEntryOptionsError('');
    }).catch(err => {
      setEntryOptionsError(err instanceof Error ? err.message : 'Unable to load payment-entry lists.');
    }).finally(() => setEntryOptionsLoading(false));
  }

  useEffect(() => { loadEntryOptions(); }, []);

  useEffect(() => {
    if (!entryOnly) return;
    setProjectsLoading(true);
    api<ProjectRecord[]>('/projects').then(setProjects)
      .catch(err => setProjectDataError(err instanceof Error ? err.message : 'Unable to load configured project locations.'))
      .finally(() => setProjectsLoading(false));
  }, [entryOnly]);

  useEffect(() => {
    if (!entryOnly || !registrationSearch.trim()) {
      setRegistrations([]);
      setRegistrationsLoading(false);
      return;
    }
    let cancelled = false;
    setRegistrationsLoading(true);
    setRegistrationError('');
    setRegistrations([]);
    const timer = window.setTimeout(() => {
      api<FinanceMemberRegistrationSearch[]>(`/finance/members/search?field=REGISTRATION_NO&q=${encodeURIComponent(registrationSearch.trim())}`)
        .then(rows => {
          if (cancelled) return;
          const options = rows.flatMap(row => String(row.registrationNos || '').split(',').map(value => value.trim())
            .filter(regNo => regNo && regNo.toLowerCase().includes(registrationSearch.trim().toLowerCase()))
            .map(regNo => ({ regNo, memberId: row.memberId, mbrNo: row.mbrNo, memberName: row.name })));
          setRegistrations(options);
        })
        .catch(err => { if (!cancelled) setRegistrationError(err instanceof Error ? err.message : 'Unable to search registration choices.'); })
        .finally(() => { if (!cancelled) setRegistrationsLoading(false); });
    }, 250);
    return () => { cancelled = true; window.clearTimeout(timer); };
  }, [entryOnly, registrationSearch]);

  useEffect(() => {
    if (!form.memberId) {
      setMemberBookings([]);
      setBookingsError('');
      setBookingsLoading(false);
      return;
    }
    let cancelled = false;
    setBookingsLoading(true);
    setBookingsError('');
    setMemberBookings([]);
    api<Record<string, unknown>[]>(`/finance/members/${form.memberId}/bookings`).then(rows => {
      if (!cancelled) setMemberBookings(rows);
    }).catch(err => {
      if (!cancelled) setBookingsError(err instanceof Error ? err.message : 'Unable to load the customer bookings.');
    }).finally(() => {
      if (!cancelled) setBookingsLoading(false);
    });
    return () => { cancelled = true; };
  }, [form.memberId]);

  useEffect(() => {
    if (!form.memberId || !form.bookingId) {
      setAllocationDues([]);
      setDuesError('');
      setDuesLoading(false);
      return;
    }
    let cancelled = false;
    setDuesLoading(true);
    setDuesError('');
    setAllocationDues([]);
    api<AllocationDueOption[]>(`/finance/members/${form.memberId}/allocation-dues?bookingId=${form.bookingId}`)
      .then(rows => { if (!cancelled) setAllocationDues(rows); })
      .catch(err => { if (!cancelled) setDuesError(err instanceof Error ? err.message : 'Unable to load available charge-head dues.'); })
      .finally(() => { if (!cancelled) setDuesLoading(false); });
    return () => { cancelled = true; };
  }, [form.memberId, form.bookingId]);

  const availableChargeTypes = Array.from(new Map(
    allocationDues.filter(due => Number(due.availableDue) > 0)
      .map(due => [String(due.chargeTypeId), { id: due.chargeTypeId, code: due.chargeCode, name: due.chargeName }])
  ).values());
  const selectedAllocationDue = allocationDues.find(due =>
    String(due.receivableId) === form.receivableId && String(due.chargeTypeId) === form.chargeTypeId);
  const selectedRegistration = registrations.find(row => row.regNo === selectedRegistrationNo);
  const financeRegistrationBookings = selectedRegistration
    ? memberBookings.filter(booking => String(booking.registrationNo || '').toLowerCase() === selectedRegistration.regNo.toLowerCase())
    : [];
  const matchedSalesBookings = selectedRegistration
    ? salesBookings.filter(booking => booking.regNo.toLowerCase() === selectedRegistration.regNo.toLowerCase()
      && Number(booking.memberId) === Number(selectedRegistration.memberId)
      && !['CANCELLED', 'MERGED'].includes(String(booking.status || '').toUpperCase()))
    : [];
  const registrationBookings: Record<string, unknown>[] = matchedSalesBookings.length
    ? matchedSalesBookings.map(booking => ({
      ...financeRegistrationBookings.find(row => String(row.id) === String(booking.id)),
      ...booking,
      id: booking.id,
      bookingNo: booking.bookingNo,
      registrationNo: booking.regNo,
      projectName: booking.projectName,
      location: projects.find(project => project.projectName === booking.projectName)?.location || '',
      sizeMarla: booking.sizeMarla,
      type: booking.usageType,
      plotNo: booking.plotNo,
      blockName: booking.blockName
    }))
    : financeRegistrationBookings;
  const roleFilteredBookings = registrationBookings.filter(booking =>
    !form.partyRoleType || String(booking.type || booking.usageType || '').toLowerCase() === form.partyRoleType.toLowerCase());
  const projectNames = Array.from(new Set(roleFilteredBookings.map(booking => String(booking.projectName || '')).filter(Boolean))).sort();
  const projectBookings = roleFilteredBookings.filter(booking => !form.projectName || String(booking.projectName || '') === form.projectName);
  const plotSizes = Array.from(new Set(projectBookings.map(booking => Number(booking.sizeMarla)).filter(size => Number.isFinite(size) && size > 0))).sort((a, b) => a - b);
  const sizeFilteredBookings = projectBookings.filter(booking => !form.plotSizeMarla || Number(booking.sizeMarla) === Number(form.plotSizeMarla));
  const selectedReceiptBooking = registrationBookings.find(booking => String(booking.id) === form.bookingId);
  const extraReceivableOptions = allocationDues.filter(due => Number(due.availableDue) > 0
    && String(due.bookingId) === form.bookingId
    && String(due.chargeTypeId) !== form.chargeTypeId
    && !additionalAllocations.some(line => {
      const selected = allocationDues.find(option => String(option.receivableId) === line.receivableId);
      return selected && Number(selected.chargeTypeId) === Number(due.chargeTypeId);
    }));

  useEffect(() => {
    if (!entryOnly || !selectedRegistration) {
      setSalesBookings([]);
      setSalesBookingsLoading(false);
      return;
    }
    let cancelled = false;
    setSalesBookingsLoading(true);
    api<SalesBooking[]>(`/sales/bookings?q=${encodeURIComponent(selectedRegistration.regNo)}`)
      .then(rows => { if (!cancelled) setSalesBookings(rows); })
      .catch(err => { if (!cancelled) setRegistrationError(err instanceof Error ? err.message : 'Unable to load this registration’s configured project and plot details.'); })
      .finally(() => { if (!cancelled) setSalesBookingsLoading(false); });
    return () => { cancelled = true; };
  }, [entryOnly, selectedRegistration?.regNo]);

  function changeRegistration(regNo: string) {
    const registration = registrations.find(row => row.regNo === regNo);
    setSelectedRegistrationNo(regNo);
    setForm(current => ({ ...current, registrationId: regNo, memberId: registration ? String(registration.memberId) : '', partyRoleType: '', projectName: '', plotSizeMarla: '', bookingId: '', chargeTypeId: '', receivableId: '', amount: '' }));
    setPrimaryAllocationAmount('');
    setAdditionalAllocations([]);
    setAllocationDues([]);
    setFormError('');
  }

  function changePartyRoleType(roleType: string) {
    setForm(current => ({ ...current, partyRoleType: roleType, projectName: '', plotSizeMarla: '', bookingId: '', chargeTypeId: '', receivableId: '', amount: '' }));
    setPrimaryAllocationAmount('');
    setAdditionalAllocations([]);
    setAllocationDues([]);
  }

  function handleMemberChange(memId: string) {
    setForm(current => ({ ...current, memberId: memId, bookingId: '', chargeTypeId: '', receivableId: '', amount: '' }));
    setAllocationDues([]);
    setFormError('');
  }

  function handleChargeTypeChange(ctId: string) {
    setForm(current => ({ ...current, chargeTypeId: ctId, receivableId: '', amount: '' }));
    setPrimaryAllocationAmount('');
    setAdditionalAllocations([]);
  }

  function handlePaymentMethodChange(method: string) {
    setForm(current => ({
      ...current,
      method,
      bankInstrumentType: method === 'CASH' ? '' : current.bankInstrumentType,
      ...(method === 'CASH'
        ? { bankId: '', receivingAccount: '', referenceNo: '' }
        : { cashSiteId: '' })
    }));
  }

  function openNewRecovery() {
    setForm({
      memberId: '',
      registrationId: '',
      partyRoleType: '',
      projectName: '',
      plotSizeMarla: '',
      bookingId: '',
      chargeTypeId: '',
      receivableId: '',
      amount: '',
      paymentDate: new Date().toISOString().slice(0, 10),
      method: 'BANK_TRANSFER',
      paymentReference: '',
      salesClassification: '',
      bankInstrumentType: '',
      bankId: '',
      receivingAccount: '',
      referenceNo: '',
      depositDate: new Date().toISOString().slice(0, 10),
      cashSiteId: '',
      remarks: ''
    });
    setAllocationDues([]);
    setPrimaryAllocationAmount('');
    setAdditionalAllocations([]);
    setEvidenceFile(null);
    setFormError('');
    setModalRecovery(true);
  }

  async function submitRecovery(e: React.FormEvent) {
    e.preventDefault();
    if (recoverySubmitLock.current || saving) return;
    if (entryOptionsError) {
      setFormError('Payment-entry lists did not load successfully. Refresh before submitting.');
      return;
    }
    if (entryOnly && (registrationError || projectDataError || registrationsLoading || projectsLoading)) {
      setFormError(registrationError || projectDataError || 'Registration and project details are still loading. Please wait before submitting.');
      return;
    }
    if (!form.memberId) {
      setFormError('Customer / Member is required.');
      return;
    }
    if (!form.bookingId) {
      setFormError('A sale/booking must be selected for this payment.');
      return;
    }
    if (!form.chargeTypeId || !form.receivableId || !selectedAllocationDue) {
      setFormError('Select the charge head and its specific receivable/due line.');
      return;
    }
    if (Number(selectedAllocationDue.availableDue) <= 0) {
      setFormError('The selected charge head has no remaining amount due.');
      return;
    }
    if (!form.paymentDate) {
      setFormError('Payment date is required.');
      return;
    }
    if (!form.amount || Number(form.amount) <= 0) {
      setFormError('Payment amount must be greater than zero.');
      return;
    }
    if (!entryOnly && Number(form.amount) > Number(selectedAllocationDue.availableDue)) {
      setFormError(`Amount exceeds the available due for ${selectedAllocationDue.chargeName} (${formatMoney(Number(selectedAllocationDue.availableDue))}). No payment was submitted.`);
      return;
    }
    let allocationLines: Array<Record<string, unknown>>;
    if (entryOnly) {
      if (!selectedRegistration || !form.partyRoleType || !form.projectName || !form.plotSizeMarla
        || String(selectedReceiptBooking?.type || selectedReceiptBooking?.usageType || '').toLowerCase() !== form.partyRoleType.toLowerCase()
        || String(selectedReceiptBooking?.regNo || selectedReceiptBooking?.registrationNo || '').toLowerCase() !== selectedRegistration.regNo.toLowerCase()
        || Number(selectedReceiptBooking?.memberId) !== Number(selectedRegistration.memberId)
        || String(selectedReceiptBooking?.projectName || '') !== form.projectName
        || Number(selectedReceiptBooking?.sizeMarla) !== Number(form.plotSizeMarla)) {
        setFormError('Select a valid party role, registration, configured project, plot size and matching booking.');
        return;
      }
      const cents = (value: string) => /^\d+(?:\.\d{1,2})?$/.test(value.trim()) ? Math.round(Number(value) * 100) : NaN;
      const totalCents = cents(form.amount);
      const primaryCents = cents(primaryAllocationAmount);
      if (!Number.isFinite(totalCents) || !Number.isFinite(primaryCents) || totalCents <= 0 || primaryCents <= 0) {
        setFormError('Receipt amount and the selected charge allocation must be positive amounts with no more than two decimal places.');
        return;
      }
      if (primaryCents > Math.round(Number(selectedAllocationDue.availableDue) * 100)) {
        setFormError(`The selected charge head allocation exceeds its outstanding balance of ${formatMoney(Number(selectedAllocationDue.availableDue))}.`);
        return;
      }
      const extraLines = additionalAllocations.map(line => {
        const due = allocationDues.find(option => String(option.receivableId) === line.receivableId);
        return { due, cents: cents(line.amount) };
      });
      if (extraLines.some(line => !line.due || !Number.isFinite(line.cents) || line.cents <= 0)) {
        setFormError('Each additional charge allocation needs an eligible due line and a positive amount.');
        return;
      }
      const extras = extraLines as Array<{ due: AllocationDueOption; cents: number }>;
      if (extras.some(line => String(line.due.bookingId) !== form.bookingId
        || String(line.due.chargeTypeId) === form.chargeTypeId
        || line.cents > Math.round(Number(line.due.availableDue) * 100))) {
        setFormError('Additional allocations must use another eligible charge head in this booking and stay within its outstanding balance.');
        return;
      }
      if (new Set(extras.map(line => String(line.due.chargeTypeId))).size !== extras.length
        || new Set(extras.map(line => String(line.due.receivableId))).size !== extras.length) {
        setFormError('A charge head or due line can only be allocated once.');
        return;
      }
      const allocatedCents = primaryCents + extras.reduce((sum, line) => sum + line.cents, 0);
      if (allocatedCents !== totalCents) {
        const remaining = totalCents - allocatedCents;
        const remainingMessage = remaining > 0 ? ` Allocate the remaining ${formatMoney(remaining / 100)} to another eligible charge head.` : ' Reduce the allocated amount to match the receipt.';
        setFormError(`Allocation total must equal receipt amount.${remainingMessage}`);
        return;
      }
      if (totalCents > Math.round(Number(selectedAllocationDue.availableDue) * 100) && extras.length === 0) {
        setFormError('The receipt exceeds this charge head balance. Add another eligible charge head for the excess amount.');
        return;
      }
      allocationLines = [{
        lineNo: 1, registrationId: selectedAllocationDue.registrationId, bookingId: selectedAllocationDue.bookingId,
        plotId: selectedAllocationDue.plotId, chargeTypeId: selectedAllocationDue.chargeTypeId,
        receivableId: selectedAllocationDue.receivableId, installmentScheduleId: null,
        allocatedAmount: primaryCents / 100, lineRemarks: form.remarks.trim() || null
      }, ...extras.map((line, index) => ({
        lineNo: index + 2, registrationId: line.due.registrationId, bookingId: line.due.bookingId,
        plotId: line.due.plotId, chargeTypeId: line.due.chargeTypeId, receivableId: line.due.receivableId,
        installmentScheduleId: null, allocatedAmount: line.cents / 100, lineRemarks: form.remarks.trim() || null
      }))];
    } else {
      allocationLines = [{
        lineNo: 1, registrationId: selectedAllocationDue.registrationId, bookingId: selectedAllocationDue.bookingId,
        plotId: selectedAllocationDue.plotId, chargeTypeId: selectedAllocationDue.chargeTypeId,
        receivableId: selectedAllocationDue.receivableId, installmentScheduleId: null,
        allocatedAmount: Number(form.amount), lineRemarks: form.remarks.trim() || null
      }];
    }
    if (entryOnly && !form.salesClassification) {
      setFormError('Sales Classification is required. Select Sales, Charges, or Variation.');
      return;
    }
    if (entryOnly && form.method !== 'CASH' && !form.bankInstrumentType) {
      setFormError('Bank Instrument Type is required for bank payments. Select Online Transfer, Cash, Cheque, DD, or PO.');
      return;
    }
    if (form.method === 'CASH' && !form.cashSiteId) {
      setFormError('Cash payments require a Cash Collection Site.');
      return;
    }
    if (form.method !== 'CASH' && !form.bankId) {
      setFormError('Bank, Online and Cheque payments require a receiving bank.');
      return;
    }
    if (form.method !== 'CASH' && !form.referenceNo.trim()) {
      setFormError('Bank / Online / Cheque payments require a Bank Document No. or Transaction ID / reference.');
      return;
    }
    if (form.method !== 'CASH' && !form.depositDate) {
      setFormError('Bank / Online / Cheque payments require a deposit or instrument date.');
      return;
    }
    recoverySubmitLock.current = true;
    setSaving(true);
    setFormError('');

    try {
      const selectedBank = banks.find(b => String(b.id) === form.bankId);
      const selectedCashSite = cashSites.find(cs => String(cs.id) === form.cashSiteId);

      const payload = {
        memberId: Number(form.memberId),
        amount: Number(form.amount),
        allocationScopeBookingId: entryOnly ? Number(form.bookingId) : null,
        partyRoleType: entryOnly ? form.partyRoleType : null,
        paymentDate: form.paymentDate,
        method: form.method,
        paymentReference: form.paymentReference.trim() || null,
        salesClassification: entryOnly ? form.salesClassification : null,
        bankInstrumentType: entryOnly && form.method !== 'CASH' ? form.bankInstrumentType : null,
        bankId: form.method !== 'CASH' && form.bankId ? Number(form.bankId) : null,
        bankName: selectedBank ? String(selectedBank.bankName) : '',
        receivingAccount: form.method !== 'CASH' ? form.receivingAccount || (selectedBank ? String(selectedBank.accountNo) : '') : null,
        referenceNo: form.referenceNo.trim() || null,
        depositDate: form.method !== 'CASH' ? form.depositDate : null,
        cashSiteId: form.method === 'CASH' && form.cashSiteId ? Number(form.cashSiteId) : null,
        cashSite: selectedCashSite ? String(selectedCashSite.siteName) : '',
        remarks: form.remarks,
        allocationLines
      };

      const result = await api<{ id: number; receiptNo: string }>('/finance/recovery', {
        method: 'POST',
        body: JSON.stringify(payload)
      });

      if (evidenceFile && result.id) {
        await uploadFile(`/finance/payments/${result.id}/evidence`, evidenceFile);
      }

      if (entryOnly) {
        setReceiptCreated(`Receipt ${result.receiptNo} was recorded and is pending Finance verification.`);
        setModalRecovery(false);
        setForm({ memberId: '', registrationId: '', partyRoleType: '', projectName: '', plotSizeMarla: '', bookingId: '', chargeTypeId: '', receivableId: '', amount: '', paymentDate: new Date().toISOString().slice(0, 10), method: 'CASH', paymentReference: '', salesClassification: '', bankInstrumentType: '', bankId: '', receivingAccount: '', referenceNo: '', depositDate: new Date().toISOString().slice(0, 10), cashSiteId: '', remarks: '' });
        setSelectedRegistrationNo('');
        setPrimaryAllocationAmount('');
        setAdditionalAllocations([]);
        setAllocationDues([]);
      } else setModalRecovery(false);
      loadData();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Failed to record recovery entry.');
    } finally {
      recoverySubmitLock.current = false;
      setSaving(false);
    }
  }

  async function openPaymentDetails(payment: PaymentRecord) {
    setDetailPayment(payment);
    setPaymentAllocations([]);
    setAllocationError('');
    setAllocationsLoading(true);
    try {
      setPaymentAllocations(await api<PaymentAllocationRecord[]>(`/finance/payments/${payment.id}/allocations`));
    } catch (err) {
      setAllocationError(err instanceof Error ? err.message : 'Unable to load payment allocations.');
    } finally {
      setAllocationsLoading(false);
    }
  }

  async function printPayment(payment: PaymentRecord) {
    await openPaymentDetails(payment);
    // paymentAllocations state is populated by openPaymentDetails — allow one tick for state to settle
    window.setTimeout(() => printReceiptDocument(payment, paymentAllocations), 350);
  }

  function printReceiptDocument(payment: PaymentRecord, allocations: PaymentAllocationRecord[]) {
    const win = window.open('', '_blank');
    if (!win) { window.print(); return; }

    // Helpers
    const money = (v: number) => new Intl.NumberFormat('en-PK', { minimumFractionDigits: 0, maximumFractionDigits: 0 }).format(v || 0);
    const text = (v: unknown) => (v == null || v === '') ? '' : String(v);

    // Amount in words (basic implementation for PKR)
    function amountInWords(n: number): string {
      if (n <= 0) return 'Zero Rupees';
      const ones = ['','One','Two','Three','Four','Five','Six','Seven','Eight','Nine',
        'Ten','Eleven','Twelve','Thirteen','Fourteen','Fifteen','Sixteen','Seventeen','Eighteen','Nineteen'];
      const tens = ['','','Twenty','Thirty','Forty','Fifty','Sixty','Seventy','Eighty','Ninety'];
      function below100(x: number): string {
        return x < 20 ? ones[x] : tens[Math.floor(x/10)] + (x % 10 ? ' ' + ones[x % 10] : '');
      }
      function below1000(x: number): string {
        return x >= 100 ? ones[Math.floor(x/100)] + ' Hundred' + (x % 100 ? ' ' + below100(x % 100) : '') : below100(x);
      }
      const crore = Math.floor(n / 10000000); n %= 10000000;
      const lakh  = Math.floor(n / 100000);   n %= 100000;
      const thou  = Math.floor(n / 1000);     n %= 1000;
      const rest  = n;
      const parts: string[] = [];
      if (crore) parts.push(below1000(crore) + ' Crore');
      if (lakh)  parts.push(below1000(lakh)  + ' Lakh');
      if (thou)  parts.push(below1000(thou)  + ' Thousand');
      if (rest)  parts.push(below1000(rest));
      return parts.join(' ') + ' Rupees';
    }

    // Map existing payment fields to receipt challan fields
    const formNo      = text(payment.registrationNo) || 'N/A';
    const receiptRef  = text(payment.receiptNo);
    const receiptDate = text(payment.paymentDate ? String(payment.paymentDate).slice(0,10) : null);
    const custName    = text(payment.memberName);
    const mbrNo       = text(payment.mbrNo);
    // Pay Head: use first allocation charge name, fall back to header charge
    const payHead     = allocations.length > 0 ? text(allocations[0].chargeName) : text(payment.chargeName);
    // Instrument fields
    const isCash      = (payment.method || '').toUpperCase() === 'CASH';
    const instNo      = isCash ? 'N/A' : text(payment.referenceNo);
    const instDate    = isCash ? 'N/A' : text(payment.depositDate ? String(payment.depositDate).slice(0,10) : null);
    const instBank    = isCash ? (text(payment.cashSiteName) || 'N/A') : (text(payment.bankInstrumentType) || text(payment.method));
    const instCity    = 'N/A';
    const amt         = Number(payment.amount) || 0;
    const amtWords    = amountInWords(Math.round(amt));
    const remarks     = allocations.length > 0
      ? allocations.map(a => text(a.chargeName) + (a.receivableDescription ? ' — ' + a.receivableDescription : '')).filter(Boolean).join('; ')
      : text(payment.remarks);
    const printTimestamp = new Date().toLocaleString('en-PK', { day:'2-digit', month:'numeric', year:'numeric', hour:'2-digit', minute:'2-digit', second:'2-digit', hour12:false }).replace(',','');

    // Build one copy of the challan body
    function challanBody(copyLabel: string, isCustomerCopy: boolean): string {
      return `
        <div class="challan-copy">
          <div class="challan-header">
            <div class="challan-brand">
              <div class="brand-icon">1</div>
              <div class="brand-text">
                <div class="brand-name">District One Lahore</div>
              </div>
            </div>
            <div class="challan-title-block">
              <div class="challan-title">Receipt Challan</div>
              <table class="challan-meta"><tbody>
                <tr><td class="meta-label">Challan no.</td><td class="meta-value">${receiptRef}</td></tr>
                <tr><td class="meta-label">MBR</td><td class="meta-value">${mbrNo}</td></tr>
                <tr><td class="meta-label">Print Date.</td><td class="meta-value">${printTimestamp.split(' ')[0]}</td></tr>
              </tbody></table>
            </div>
          </div>
          <table class="challan-fields"><tbody>
            <tr><td class="field-label">Form No.</td><td class="field-value">${formNo}</td></tr>
            <tr><td class="field-label">Receipt Ref #</td><td class="field-value">${receiptRef}</td></tr>
            <tr><td class="field-label">Receipt Date.</td><td class="field-value">${receiptDate}</td></tr>
            <tr><td class="field-label">Name</td><td class="field-value">${custName}</td></tr>
            <tr><td class="field-label">Pay Head.</td><td class="field-value">${payHead}</td></tr>
            <tr><td class="field-label">Instrument #</td><td class="field-value">${instNo}</td></tr>
            <tr><td class="field-label">Inst. Date</td><td class="field-value">${instDate}</td></tr>
            <tr><td class="field-label">Inst. Bank</td><td class="field-value">${instBank}</td></tr>
            <tr><td class="field-label">Inst. City</td><td class="field-value">${instCity}</td></tr>
            <tr><td class="field-label">Amount</td><td class="field-value amount-val">Rs. ${money(amt)}</td></tr>
            <tr><td class="field-label">Amount<br/>(In Words)</td><td class="field-value">${amtWords}</td></tr>
            <tr><td class="field-label">Remarks</td><td class="field-value">${remarks}</td></tr>
          </tbody></table>
          <div class="challan-footer">
            ${isCustomerCopy
              ? `<div class="office-only-box"></div><div class="copy-label">Customer Copy</div>`
              : `<div class="office-only-box for-office">For Office Use Only</div><div class="copy-label">${copyLabel}</div>`
            }
            <div class="sig-row">
              <span>Customer Signature</span>
              <span class="sig-timestamp">${printTimestamp}</span>
            </div>
            ${isCustomerCopy ? `<div class="subject-note">(Subject to realization of Instrument.)</div>` : ''}
          </div>
        </div>`;
    }

    const html = `<!doctype html><html><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <title>Receipt Challan ${receiptRef}</title>
    <style>
      * { box-sizing: border-box; margin: 0; padding: 0; }
      body { font-family: Arial, Helvetica, sans-serif; font-size: 8.5pt; color: #111; background: #fff; }
      .challan-page { display: flex; flex-direction: row; gap: 0; width: 100%; }
      .challan-copy { flex: 1; border: 1px solid #222; padding: 8px 10px; min-width: 0; }
      .challan-copy + .challan-copy { border-left: 0; }
      /* Header */
      .challan-header { display: flex; align-items: flex-start; justify-content: space-between; border-bottom: 2px solid #1a5c8a; padding-bottom: 6px; margin-bottom: 6px; }
      .challan-brand { display: flex; align-items: center; gap: 5px; }
      .brand-icon { width: 28px; height: 28px; background: #1a5c8a; color: #fff; font-size: 14pt; font-weight: 900; display: flex; align-items: center; justify-content: center; border-radius: 3px; }
      .brand-text .brand-name { font-size: 10pt; font-weight: 700; color: #1a5c8a; line-height: 1.2; }
      .brand-text .brand-sub { font-size: 7pt; color: #555; }
      .challan-title-block { text-align: right; }
      .challan-title { font-size: 11pt; font-weight: 700; color: #1a5c8a; border-bottom: 1px solid #1a5c8a; margin-bottom: 3px; padding-bottom: 2px; }
      .challan-meta { font-size: 7pt; }
      .challan-meta td { padding: 0 2px; }
      .meta-label { color: #555; white-space: nowrap; }
      .meta-value { font-weight: 700; padding-left: 4px; }
      /* Fields table */
      .challan-fields { width: 100%; border-collapse: collapse; }
      .challan-fields tr { border-bottom: 1px solid #ddd; }
      .field-label { width: 42%; font-weight: 600; color: #333; padding: 3px 4px 3px 0; vertical-align: top; white-space: nowrap; font-size: 7.5pt; }
      .field-value { padding: 3px 0 3px 4px; color: #111; font-size: 7.5pt; word-break: break-word; }
      .amount-val { font-weight: 700; font-size: 8.5pt; }
      /* Footer */
      .challan-footer { margin-top: 6px; border-top: 1px solid #888; padding-top: 4px; }
      .office-only-box { min-height: 24px; border: 1px solid #aaa; margin-bottom: 4px; font-size: 7pt; color: #555; display: flex; align-items: center; justify-content: center; }
      .for-office { background: #f5f5f5; font-style: italic; }
      .copy-label { text-align: center; font-weight: 700; font-size: 8pt; color: #1a5c8a; margin: 3px 0; }
      .sig-row { display: flex; justify-content: space-between; font-size: 7pt; color: #333; margin-top: 4px; border-top: 1px solid #888; padding-top: 2px; }
      .sig-timestamp { font-size: 6.5pt; color: #555; }
      .subject-note { font-size: 6.5pt; color: #555; text-align: right; margin-top: 2px; }
      @media print {
        @page { size: A4 landscape; margin: 8mm; }
        body { font-size: 8pt; }
        .challan-page { page-break-inside: avoid; }
      }
    </style></head><body>
    <div class="challan-page">
      ${challanBody('Data Center Copy', false)}
      ${challanBody('Accounts Copy', false)}
      ${challanBody('Customer Copy', true)}
    </div>
    <script>window.onload = function(){ window.print(); }</script>
    </body></html>`;

    win.document.open();
    win.document.write(html);
    win.document.close();
  }

  function openVerification(payment: PaymentRecord) {
    setVerificationPayment(payment);
    setVerifiedSourceAmount(String(payment.amount));
    setVerifiedSourceReference('');
    setVerificationRemarks('');
    setPaymentAllocations([]);
    setAllocationError('');
    setAllocationsLoading(true);
    api<PaymentAllocationRecord[]>(`/finance/payments/${payment.id}/allocations`)
      .then(setPaymentAllocations)
      .catch(err => setAllocationError(err instanceof Error ? err.message : 'Unable to load payment allocations.'))
      .finally(() => setAllocationsLoading(false));
  }

  async function submitVerification(e: React.FormEvent) {
    e.preventDefault();
    if (!verificationPayment) return;
    setVerifying(true);
    try {
      await api(`/finance/payments/${verificationPayment.id}/verify`, {
        method: 'POST',
        body: JSON.stringify({
          verifiedSourceAmount: Number(verifiedSourceAmount),
          sourceReference: verifiedSourceReference.trim() || null,
          remarks: verificationRemarks.trim() || null
        })
      });
      setVerificationPayment(null);
      loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Verification failed.');
    } finally {
      setVerifying(false);
    }
  }

  async function postPayment(payment: PaymentRecord) {
    try {
      await api(`/finance/payments/${payment.id}/post`, { method: 'POST' });
      loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Posting failed.');
    }
  }

  async function reversePayment(payment: PaymentRecord) {
    const reason = window.prompt(`Enter the Finance reversal reason for ${payment.receiptNo}:`);
    if (!reason?.trim()) return;
    if (!window.confirm(`Reverse receipt ${payment.receiptNo}? This records a financial reversal.`)) return;
    try {
      await api(`/finance/payments/${payment.id}/reverse`, { method: 'POST', body: JSON.stringify({ reason: reason.trim() }) });
      loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Receipt reversal failed.');
    }
  }

  async function submitRejection(e: React.FormEvent) {
    e.preventDefault();
    if (!rejectPaymentItem || !rejectReason.trim()) return;

    setRejecting(true);
    try {
      await api(`/finance/payments/${rejectPaymentItem.id}/reject`, {
        method: 'POST',
        body: JSON.stringify({ reason: rejectReason.trim() })
      });
      setRejectPaymentItem(null);
      setRejectReason('');
      loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Rejection failed.');
    } finally {
      setRejecting(false);
    }
  }

  return (
    <>
      {entryOnly && <div className="panel receipt-management-panel">
        <div className="panel-head">
          <div><p className="eyebrow">FINANCE / RECEIPT MANAGEMENT</p><h3>RES Receipt</h3></div>
          <div className="finance-filter">
            {canPerformAction('Finance', 'RECEIPT_CREATE') && <button className="primary" onClick={openNewRecovery} disabled={entryOptionsLoading || !!entryOptionsError}><Plus size={15}/> Add Receipt</button>}
            {entryOptionsLoading && <span className="muted" style={{fontSize:'12px'}}>Loading…</span>}
            {entryOptionsError && <button className="secondary" onClick={loadEntryOptions}><RefreshCw size={14}/> Retry</button>}
            {onBack && <button className="secondary" onClick={onBack}><ArrowLeft size={15}/> Back</button>}
          </div>
        </div>
        <div className="form-grid receipt-management-filters">
          <label>From Date<input type="date" value={receiptFilters.fromDate} onChange={e => { setReceiptPage(0); setReceiptFilters({...receiptFilters, fromDate:e.target.value}); }}/></label>
          <label>To Date<input type="date" value={receiptFilters.toDate} onChange={e => { setReceiptPage(0); setReceiptFilters({...receiptFilters, toDate:e.target.value}); }}/></label>
          <label>Receipt / Document No.<input value={receiptFilters.receiptNo} onChange={e => { setReceiptPage(0); setReceiptFilters({...receiptFilters, receiptNo:e.target.value}); }}/></label>
          <label>Registration Number<input value={receiptFilters.registrationNo} onChange={e => { setReceiptPage(0); setReceiptFilters({...receiptFilters, registrationNo:e.target.value}); }}/></label>
          <label>Customer / Party Name<input value={receiptFilters.partyName} onChange={e => { setReceiptPage(0); setReceiptFilters({...receiptFilters, partyName:e.target.value}); }}/></label>
          <label>Project<input value={receiptFilters.projectName} onChange={e => { setReceiptPage(0); setReceiptFilters({...receiptFilters, projectName:e.target.value}); }}/></label>
          <label>Location<input value={receiptFilters.location} onChange={e => { setReceiptPage(0); setReceiptFilters({...receiptFilters, location:e.target.value}); }}/></label>
          <label>Payment Method<select value={receiptFilters.paymentMethod} onChange={e => { setReceiptPage(0); setReceiptFilters({...receiptFilters, paymentMethod:e.target.value}); }}><option value="ALL">All</option><option value="CASH">Cash</option><option value="BANK">Bank</option></select></label>
          <label>Bank Document / Reference<input value={receiptFilters.bankDocument} onChange={e => { setReceiptPage(0); setReceiptFilters({...receiptFilters, bankDocument:e.target.value}); }}/></label>
          <label>Posted to General Ledger<select value={receiptFilters.postedToGl} onChange={e => { setReceiptPage(0); setReceiptFilters({...receiptFilters, postedToGl:e.target.value}); }}><option value="ALL">All</option><option value="YES">Yes</option><option value="NO">No</option></select></label>
          <label>Finance Approval<select value={receiptFilters.approvalStatus} onChange={e => { setReceiptPage(0); setReceiptFilters({...receiptFilters, approvalStatus:e.target.value}); }}><option value="ALL">All</option><option value="PENDING">Pending Finance Verification</option><option value="APPROVED">Verified / Approved</option><option value="REJECTED">Rejected</option></select></label>
          <label>Receipt Status<select value={receiptFilters.receiptStatus} onChange={e => { setReceiptPage(0); setReceiptFilters({...receiptFilters, receiptStatus:e.target.value}); }}><option value="ALL">All</option><option value="RECOVERY_ENTRY">Recovery Entry / Pending</option><option value="VERIFIED">Verified</option><option value="POSTED">Posted</option><option value="REJECTED">Rejected</option><option value="REVERSED">Reversed</option></select></label>
          <button className="secondary" onClick={() => { setReceiptPage(0); setReceiptFilters({fromDate:'',toDate:'',receiptNo:'',registrationNo:'',partyName:'',projectName:'',location:'',paymentMethod:'ALL',bankDocument:'',postedToGl:'ALL',approvalStatus:'ALL',receiptStatus:'ALL'}); }}>Clear Filters</button>
          <button className="secondary" onClick={loadData}><RefreshCw size={15}/> Fetch Receipts</button>
        </div>
        {error && <div className="error page-error">{error}</div>}
        {entryOptionsError && <div className="error page-error">Receipt entry is unavailable: {entryOptionsError} <button className="secondary" style={{marginLeft:'8px'}} onClick={loadEntryOptions}><RefreshCw size={13}/> Retry</button></div>}
        {receiptCreated && <div className="success page-error" role="status">{receiptCreated}</div>}
        {loading ? <div className="empty">Loading receipts…</div> : payments.length === 0 ? <div className="empty">No receipts match the selected filters.</div> : <div className="table-wrap"><table><thead><tr><th>Location</th><th>Receipt No.</th><th>Registration</th><th>Customer / Party</th><th>Receipt Date</th><th>Payment Reference</th><th>Bank Document No.</th><th>Amount</th><th>Posted to GL</th><th>Finance Approval</th><th>Status</th><th>Actions</th></tr></thead><tbody>{payments.map(p => <tr key={p.id}>
          <td>{p.location || '—'}</td><td className="code">{p.receiptNo}</td><td>{p.registrationNo || '—'}</td><td>{p.memberName}</td><td>{String(p.paymentDate || '').slice(0,10)}</td><td>{p.paymentReference || '—'}</td><td>{p.method === 'CASH' ? '—' : p.referenceNo || '—'}</td><td>{formatMoney(Number(p.amount))}</td><td>{p.postedToGl || 'No'}</td><td>{p.approvalStatus || p.status}</td><td>{p.status}</td><td className="receipt-actions">
            <button className="secondary compact" onClick={() => openPaymentDetails(p)}>View</button>
            {p.status === 'RECOVERY_ENTRY' && <button className="secondary compact" disabled title="Receipt editing is not exposed by the existing backend API">Edit</button>}
            <button className="secondary compact" onClick={() => printPayment(p)}>Print</button>
            {p.status === 'RECOVERY_ENTRY' && <span className="muted">Pending Finance Verification</span>}
            {(p.status === 'RECOVERY_ENTRY' || p.status === 'PENDING' || p.status === 'PENDING_VERIFICATION')
              && canPerformAction('Finance', 'RECEIPT_VERIFY') && <button className="primary compact" onClick={() => openVerification(p)}>Verify</button>}
            {p.status === 'VERIFIED' && canPerformAction('Finance', 'RECEIPT_POST')
              && <button className="primary compact" onClick={() => postPayment(p)}>Post</button>}
            {p.status === 'POSTED' && canPerformAction('Finance', 'RECEIPT_REVERSE')
              && <button className="secondary compact" onClick={() => reversePayment(p)}>Reverse</button>}
          </td></tr>)}</tbody></table></div>}
        <div className="panel-head"><small>Page {receiptPage + 1} · showing at most {receiptPageSize} receipts</small><div className="finance-filter"><button className="secondary" disabled={receiptPage === 0 || loading} onClick={() => setReceiptPage(page => Math.max(0,page-1))}>Previous</button><button className="secondary" disabled={payments.length < receiptPageSize || loading} onClick={() => setReceiptPage(page => page+1)}>Next</button></div></div>
      </div>}
      {!entryOnly && <><div className="stat-grid">
        <div className="stat">
          <WalletCards size={18} className="stat-icon" />
          <span>Posted Collection</span>
          <strong>{summary ? formatMoney(Number(summary.postedCollection || 0)) : '?'}</strong>
          <small>Verified postings</small>
        </div>
        <div className="stat">
          <ShieldCheck size={18} className="stat-icon" />
          <span>Pending Verification</span>
          <strong>{summary ? Number(summary.pendingReview || 0) : 0}</strong>
          <small>Requires finance review</small>
        </div>
        <div className="stat">
          <ReceiptText size={18} className="stat-icon" />
          <span>Total Receipts Issued</span>
          <strong>{summary ? Number(summary.receipts || 0) : 0}</strong>
          <small>Permanent DO-xxxxxx sequence</small>
        </div>
        <div className="stat">
          <CalendarClock size={18} className="stat-icon" />
          <span>Today Collection</span>
          <strong>{summary ? formatMoney(Number(summary.todayCollection || 0)) : '?'}</strong>
          <small>Current day verified/posted</small>
        </div>
      </div>

      <div className="finance-mgmt-panel">
        <div className="finance-mgmt-content">
          <div className="finance-mgmt-text">
            <p className="eyebrow">DISTRICT ONE HOUSING — FINANCE</p>
            <h2 className="finance-mgmt-heading">Efficient Financial Management<br/>for a Growing Community</h2>
            <p className="finance-mgmt-desc">
              District One's integrated finance platform handles end-to-end payment recovery, receipt management,
              finance verification, and financial reporting — keeping every transaction accurate, auditable, and
              compliant with established approval and posting controls.
            </p>
          </div>
          <div className="finance-mgmt-illustration" aria-hidden="true">
            <svg viewBox="0 0 420 280" fill="none" xmlns="http://www.w3.org/2000/svg" className="finance-mgmt-svg">
              {/* Sky / background */}
              <rect width="420" height="280" rx="14" fill="#eaf5f8"/>
              {/* Ground */}
              <rect x="0" y="210" width="420" height="70" rx="0" fill="#cde8f0"/>
              <rect x="0" y="210" width="420" height="8" fill="#b5d8e8"/>
              {/* Building left */}
              <rect x="30" y="110" width="70" height="100" rx="4" fill="#0d638e"/>
              <rect x="38" y="120" width="14" height="18" rx="2" fill="#aad4e8"/>
              <rect x="60" y="120" width="14" height="18" rx="2" fill="#aad4e8"/>
              <rect x="38" y="150" width="14" height="18" rx="2" fill="#aad4e8"/>
              <rect x="60" y="150" width="14" height="18" rx="2" fill="#aad4e8"/>
              <rect x="38" y="180" width="14" height="18" rx="2" fill="#aad4e8"/>
              <rect x="60" y="180" width="14" height="18" rx="2" fill="#aad4e8"/>
              <rect x="47" y="170" width="26" height="40" rx="2" fill="#084c70"/>
              {/* Building centre — taller */}
              <rect x="130" y="70" width="90" height="140" rx="4" fill="#315267"/>
              <rect x="140" y="82" width="16" height="20" rx="2" fill="#7ec8dc"/>
              <rect x="164" y="82" width="16" height="20" rx="2" fill="#7ec8dc"/>
              <rect x="188" y="82" width="16" height="20" rx="2" fill="#7ec8dc"/>
              <rect x="140" y="114" width="16" height="20" rx="2" fill="#7ec8dc"/>
              <rect x="164" y="114" width="16" height="20" rx="2" fill="#7ec8dc"/>
              <rect x="188" y="114" width="16" height="20" rx="2" fill="#7ec8dc"/>
              <rect x="140" y="146" width="16" height="20" rx="2" fill="#7ec8dc"/>
              <rect x="164" y="146" width="16" height="20" rx="2" fill="#7ec8dc"/>
              <rect x="188" y="146" width="16" height="20" rx="2" fill="#7ec8dc"/>
              <rect x="158" y="178" width="34" height="32" rx="2" fill="#0d638e"/>
              {/* Building right */}
              <rect x="260" y="130" width="80" height="80" rx="4" fill="#1a6e94"/>
              <rect x="270" y="140" width="14" height="16" rx="2" fill="#aad4e8"/>
              <rect x="292" y="140" width="14" height="16" rx="2" fill="#aad4e8"/>
              <rect x="314" y="140" width="14" height="16" rx="2" fill="#aad4e8"/>
              <rect x="270" y="166" width="14" height="16" rx="2" fill="#aad4e8"/>
              <rect x="292" y="166" width="14" height="16" rx="2" fill="#aad4e8"/>
              <rect x="314" y="166" width="14" height="16" rx="2" fill="#aad4e8"/>
              <rect x="286" y="182" width="28" height="28" rx="2" fill="#084c70"/>
              {/* Finance document overlay */}
              <rect x="320" y="52" width="82" height="106" rx="6" fill="#fff" stroke="#cde8f0" strokeWidth="1.5"/>
              <rect x="330" y="64" width="62" height="6" rx="3" fill="#0d638e"/>
              <rect x="330" y="76" width="48" height="4" rx="2" fill="#d4e8f0"/>
              <rect x="330" y="86" width="54" height="4" rx="2" fill="#d4e8f0"/>
              <rect x="330" y="96" width="40" height="4" rx="2" fill="#d4e8f0"/>
              <rect x="330" y="108" width="62" height="1.5" fill="#e3eef3"/>
              <rect x="330" y="116" width="38" height="4" rx="2" fill="#0d638e" opacity=".35"/>
              <rect x="372" y="114" width="28" height="8" rx="3" fill="#0d638e"/>
              <rect x="330" y="128" width="62" height="1.5" fill="#e3eef3"/>
              <rect x="330" y="136" width="28" height="4" rx="2" fill="#d4e8f0"/>
              {/* Coin stack */}
              <ellipse cx="370" cy="200" rx="22" ry="7" fill="#f0c040"/>
              <rect x="348" y="188" width="44" height="12" rx="6" fill="#f5d060"/>
              <ellipse cx="370" cy="188" rx="22" ry="7" fill="#f0c040"/>
              <rect x="348" y="176" width="44" height="12" rx="6" fill="#f5d060"/>
              <ellipse cx="370" cy="176" rx="22" ry="7" fill="#f0c040"/>
              {/* Trees */}
              <circle cx="108" cy="196" r="12" fill="#4a9b6f"/>
              <rect x="105" y="204" width="6" height="10" rx="1" fill="#3a7a56"/>
              <circle cx="240" cy="200" r="10" fill="#5aaa7a"/>
              <rect x="237" y="207" width="6" height="8" rx="1" fill="#3a7a56"/>
              {/* Sun */}
              <circle cx="380" cy="38" r="18" fill="#fbbf24" opacity=".7"/>
              <circle cx="380" cy="38" r="12" fill="#fbbf24"/>
            </svg>
          </div>
        </div>
        <div className="finance-mgmt-cards">
          <div className="finance-mgmt-card" role="button" tabIndex={0} onClick={() => onNavigate?.('Receipt Entry')} onKeyDown={e => e.key === 'Enter' && onNavigate?.('Receipt Entry')} style={{ cursor: onNavigate ? 'pointer' : undefined }}>
            <div className="finance-mgmt-card-icon"><WalletCards size={22}/></div>
            <div>
              <h4>Recovery Entry</h4>
              <p>Record customer payments against outstanding dues and instalments.</p>
            </div>
          </div>
          <div className="finance-mgmt-card" role="button" tabIndex={0} onClick={() => onNavigate?.('Receipt Entry')} onKeyDown={e => e.key === 'Enter' && onNavigate?.('Receipt Entry')} style={{ cursor: onNavigate ? 'pointer' : undefined }}>
            <div className="finance-mgmt-card-icon"><ReceiptText size={22}/></div>
            <div>
              <h4>Receipt Management</h4>
              <p>Manage, filter, print, and track all issued receipts in one place.</p>
            </div>
          </div>
          <div className="finance-mgmt-card" role="button" tabIndex={0} onClick={() => onNavigate?.('Payments & Recovery')} onKeyDown={e => e.key === 'Enter' && onNavigate?.('Payments & Recovery')} style={{ cursor: onNavigate ? 'pointer' : undefined }}>
            <div className="finance-mgmt-card-icon"><ShieldCheck size={22}/></div>
            <div>
              <h4>Finance Verification</h4>
              <p>Review and verify submitted payments before they are posted to the ledger.</p>
            </div>
          </div>
          <div className="finance-mgmt-card" role="button" tabIndex={0} onClick={() => onNavigate?.('Finance Reports')} onKeyDown={e => e.key === 'Enter' && onNavigate?.('Finance Reports')} style={{ cursor: onNavigate ? 'pointer' : undefined }}>
            <div className="finance-mgmt-card-icon"><FileText size={22}/></div>
            <div>
              <h4>Financial Reports</h4>
              <p>Generate customer account statements and operational finance summaries.</p>
            </div>
          </div>
        </div>
      </div></>}

      {/* Recovery Entry Modal */}
      {recoveryModal && (
        <div className="modal-backdrop">
          <form className={entryOnly ? 'modal finance-modal-wide' : 'modal finance-modal'} onSubmit={submitRecovery}>
            <div className="modal-head">
              <div>
                <p className="eyebrow">FINANCE / RECEIPT ENTRY</p>
                <h2>{entryOnly ? 'Add Receipt' : 'Record Payment Recovery'}</h2>
                <p className="muted">Permanent receipt number DO-xxxxxx will be generated automatically by the server.</p>
              </div>
              <button type="button" className="icon" onClick={() => setModalRecovery(false)}><X /></button>
            </div>

            {receiptCreated && <div className="success page-error" role="status">{receiptCreated}</div>}

            <div className="form-grid">
              {entryOnly ? <>
              {/* ── SECTION: DOCUMENT IDENTITY ── */}
              <div className="receipt-entry-section wide"><h4>Document Identity</h4><p>Receipt number is generated safely by the server on save.</p></div>

              <label>
                Document No. / Receipt No.
                <input readOnly value="Generated on Save (DO-xxxxxx)" style={{ background: '#f1f3f5', fontStyle: 'italic', color: '#748b9a' }}/>
              </label>

              <label>
                Document Date <span style={{ color: '#b92d2d' }}>*</span>
                <input
                  required
                  type="date"
                  value={form.paymentDate}
                  onChange={e => setForm({ ...form, paymentDate: e.target.value })}
                />
              </label>

              <label className="receipt-interproject wide" style={{ flexDirection: 'row', alignItems: 'center', gap: '8px', display: 'flex', gridColumn: '1/-1' }}>
                <input type="checkbox" disabled style={{ width: 'auto', marginRight: '4px' }}/> Inter Project
                <small style={{ color: '#748b9a', fontWeight: 400 }}>Cross-project receipts are not supported by the current finance allocation rules.</small>
              </label>

              {/* ── SECTION: CUSTOMER & REGISTRATION ── */}
              <div className="receipt-entry-section wide"><h4>Customer &amp; Registration</h4></div>

              <label>
                Party Role Type <span style={{ color: '#b92d2d' }}>*</span>
                <select required value={form.partyRoleType} onChange={e => changePartyRoleType(e.target.value)}>
                  <option value="">— Select Residential / Commercial —</option>
                  <option value="Residential">Residential</option>
                  <option value="Commercial">Commercial</option>
                </select>
              </label>

              <label>
                Registration Number (Reg No) <span style={{ color: '#b92d2d' }}>*</span>
                <input type="search" placeholder="Search registration number" value={registrationSearch} onChange={e => { if (selectedRegistrationNo) changeRegistration(''); setRegistrationSearch(e.target.value); }}/>
                <select required value={selectedRegistrationNo} onChange={e => changeRegistration(e.target.value)} disabled={!form.partyRoleType || registrationsLoading || registrations.length === 0}>
                  <option value="">{registrationsLoading ? 'Searching registrations…' : registrations.length ? '— Select Registration Number —' : 'Search by registration number'}</option>
                  {registrations.map(row => <option key={`${row.memberId}-${row.regNo}`} value={row.regNo}>{row.regNo} · {row.memberName} ({row.mbrNo})</option>)}
                </select>
                {registrationError && <small className="error">{registrationError}</small>}
                {projectDataError && <small className="error">{projectDataError}</small>}
              </label>

              <label>
                Customer Name <span style={{ color: '#b92d2d' }}>*</span>
                <input required readOnly value={selectedRegistration?.memberName || ''} placeholder="Populated from the selected registration" style={{ background: '#f1f3f5' }}/>
              </label>

              {/* ── SECTION: SALE / BOOKING ── */}
              <div className="receipt-entry-section wide"><h4>Project, Plot &amp; Sale</h4></div>

              <label>
                Project <span style={{ color: '#b92d2d' }}>*</span>
                <select required disabled={!selectedRegistration || !form.partyRoleType || bookingsLoading || salesBookingsLoading} value={form.projectName}
                  onChange={e => { setForm(current => ({ ...current, projectName: e.target.value, plotSizeMarla: '', bookingId: '', chargeTypeId: '', receivableId: '', amount: '' })); setPrimaryAllocationAmount(''); setAdditionalAllocations([]); setAllocationDues([]); }}>
                  <option value="">— Select configured project —</option>
                  {projectNames.map(name => <option key={name} value={name}>{name}</option>)}
                </select>
              </label>

              <label>
                Plot Size (Marla) <span style={{ color: '#b92d2d' }}>*</span>
                <select required disabled={!form.projectName} value={form.plotSizeMarla}
                  onChange={e => { setForm(current => ({ ...current, plotSizeMarla: e.target.value, bookingId: '', chargeTypeId: '', receivableId: '', amount: '' })); setPrimaryAllocationAmount(''); setAdditionalAllocations([]); setAllocationDues([]); }}>
                  <option value="">— Select plot size —</option>
                  {plotSizes.map(size => <option key={size} value={String(size)}>{size} Marla</option>)}
                </select>
              </label>

              <label>
                Sale / Booking <span style={{ color: '#b92d2d' }}>*</span>
                <select required disabled={!form.memberId || !form.projectName || !form.plotSizeMarla || bookingsLoading || salesBookingsLoading} value={form.bookingId}
                  onChange={e => { setForm(current => ({ ...current, bookingId: e.target.value, chargeTypeId: '', receivableId: '', amount: '' })); setPrimaryAllocationAmount(''); setAdditionalAllocations([]); setAllocationDues([]); setFormError(''); }}>
                  <option value="">{bookingsLoading ? 'Loading bookings…' : '— Select linked Sale / Booking —'}</option>
                  {sizeFilteredBookings.map(b => <option key={String(b.id)} value={String(b.id)}>
                    {String(b.bookingNo || `Booking #${b.id}`)} · Reg {String(b.registrationNo || '')} · Plot {String(b.plotNo || '')} ({String(b.blockName || '')})
                  </option>)}
                </select>
                {bookingsError && <small className="error">{bookingsError}</small>}
                {selectedRegistration && !bookingsLoading && registrationBookings.length === 0 && <small>No sale or booking is linked to this registration.</small>}
              </label>

              <label>
                Location
                <input readOnly value={String(selectedReceiptBooking?.location || '— Populated from selected sale —')} style={{ background: '#f1f3f5' }}/>
              </label>

              </> : <label>
                Customer / Member <span style={{ color: '#b92d2d' }}>*</span>
                <select required value={form.memberId} onChange={e => handleMemberChange(e.target.value)}>
                  <option value="">— Select Member —</option>
                  {members.map(m => (
                    <option key={m.id} value={String(m.id)}>
                      {m.name} ({m.mbrNo}) {m.cnicNo ? `· CNIC: ${m.cnicNo}` : ''}
                    </option>
                  ))}
                </select>
              </label>}

              {!entryOnly && <label>
                Sale / Booking <span style={{ color: '#b92d2d' }}>*</span>
                <select required disabled={!form.memberId || bookingsLoading} value={form.bookingId}
                  onChange={e => {
                    setForm(current => ({ ...current, bookingId: e.target.value, chargeTypeId: '', receivableId: '', amount: '' }));
                    setAllocationDues([]);
                    setFormError('');
                  }}>
                  <option value="">{bookingsLoading ? 'Loading bookings…' : '— Select Sale / Booking —'}</option>
                  {memberBookings.map(b => (
                    <option key={String(b.id)} value={String(b.id)}>
                      {String(b.bookingNo || `Booking #${b.id}`)} · Plot {String(b.plotNo || '')} ({String(b.blockName || '')})
                    </option>
                  ))}
                </select>
                {bookingsError && <small className="error">{bookingsError}</small>}
              </label>}

              {entryOnly && <div className="receipt-entry-section wide"><h4>Due / Receivable &amp; Allocation</h4><p>Select the charge head, then the specific outstanding due line. Multiple allocations are supported.</p></div>}

              <label>
                Due / Receivable — Charge Head <span style={{ color: '#b92d2d' }}>*</span>
                <select required disabled={!form.bookingId || duesLoading || !!duesError}
                  value={form.chargeTypeId} onChange={e => handleChargeTypeChange(e.target.value)}>
                  <option value="">{duesLoading ? 'Loading available dues…' : '— Select Charge Head —'}</option>
                  {availableChargeTypes.map(ct => (
                    <option key={String(ct.id)} value={String(ct.id)}>
                      {String(ct.name)} ({String(ct.code)})
                    </option>
                  ))}
                </select>
              </label>

              <label className="wide">
                Due / Receivable — Outstanding Due Line <span style={{ color: '#b92d2d' }}>*</span>
                <select required disabled={!form.chargeTypeId || duesLoading || !!duesError}
                  value={form.receivableId}
                  onChange={e => { setForm(current => ({ ...current, receivableId: e.target.value, amount: '' })); setPrimaryAllocationAmount(''); setAdditionalAllocations([]); }}>
                  <option value="">— Select a specific due line —</option>
                  {allocationDues.filter(due => String(due.chargeTypeId) === form.chargeTypeId && Number(due.availableDue) > 0).map(due => (
                    <option key={due.receivableId} value={due.receivableId}>
                      {due.description || due.dueDate} · Due {formatMoney(Number(due.availableDue))}
                    </option>
                  ))}
                </select>
                {entryOnly && selectedAllocationDue && <small>Outstanding for this due line: {formatMoney(Number(selectedAllocationDue.availableDue))}</small>}
                {duesError && <small className="error">{duesError}</small>}
                {form.bookingId && !duesLoading && !duesError && allocationDues.length === 0 &&
                  <small>No scoped receivables are available for this sale/booking.</small>}
              </label>

              <label>
                {entryOnly ? 'Receipt Amount (PKR)' : 'Payment Amount (PKR)'} <span style={{ color: '#b92d2d' }}>*</span>
                {selectedAllocationDue && <small>Selected charge head outstanding: {formatMoney(Number(selectedAllocationDue.availableDue))}</small>}
                <input
                  required
                  type="number"
                  min="1"
                  step="0.01"
                  max={!entryOnly && selectedAllocationDue ? Number(selectedAllocationDue.availableDue) : undefined}
                  disabled={!selectedAllocationDue}
                  placeholder="0.00"
                  value={form.amount}
                  onChange={e => setForm({ ...form, amount: e.target.value })}
                />
              </label>

              {entryOnly && selectedAllocationDue && <>
                <label>
                  Amount allocated to {selectedAllocationDue.chargeName} <span style={{ color: '#b92d2d' }}>*</span>
                  <small>Maximum for this due line: {formatMoney(Number(selectedAllocationDue.availableDue))}</small>
                  <input required type="number" min="0.01" step="0.01" max={Number(selectedAllocationDue.availableDue)} value={primaryAllocationAmount}
                    onChange={e => setPrimaryAllocationAmount(e.target.value)}/>
                </label>
                {Number(form.amount) > Number(selectedAllocationDue.availableDue) && <div className="receipt-allocation-notice">
                  Excess over the selected charge head balance: <b>{formatMoney(Number(form.amount) - Number(selectedAllocationDue.availableDue))}</b>.
                  Allocate this amount explicitly to another eligible charge head below.
                  {extraReceivableOptions.length === 0 && <div className="error">No other eligible charge head is available in this booking; this receipt cannot be submitted for that amount.</div>}
                </div>}
                <div className="wide receipt-extra-allocations">
                  <div className="panel-head"><div><p className="eyebrow">CHARGE ALLOCATION</p><h3>Additional charge heads</h3></div>
                    <button type="button" className="secondary" disabled={!extraReceivableOptions.length} onClick={() => setAdditionalAllocations(lines => [...lines, { receivableId: '', amount: '' }])}><Plus size={14}/> Add allocation</button>
                  </div>
                  {additionalAllocations.map((line, index) => {
                    const selectedDue = allocationDues.find(due => String(due.receivableId) === line.receivableId);
                    const selectable = allocationDues.filter(due => Number(due.availableDue) > 0
                      && String(due.bookingId) === form.bookingId
                      && String(due.chargeTypeId) !== form.chargeTypeId
                      && !additionalAllocations.some((other, otherIndex) => {
                        if (otherIndex === index) return false;
                        const otherDue = allocationDues.find(option => String(option.receivableId) === other.receivableId);
                        return otherDue && Number(otherDue.chargeTypeId) === Number(due.chargeTypeId);
                      }));
                    return <div className="form-grid receipt-allocation-row" key={`receipt-allocation-${index}`}>
                      <label>Other Charge Head / Due <span style={{ color: '#b92d2d' }}>*</span>
                        <select required value={line.receivableId} onChange={e => setAdditionalAllocations(lines => lines.map((item, itemIndex) => itemIndex === index ? { ...item, receivableId: e.target.value, amount: '' } : item))}>
                          <option value="">— Select another outstanding charge head —</option>
                          {selectable.map(due => <option key={due.receivableId} value={String(due.receivableId)}>{due.chargeName} · {due.description || due.dueDate} · Available {formatMoney(Number(due.availableDue))}</option>)}
                        </select>
                      </label>
                      <label>Allocated Amount (PKR) <span style={{ color: '#b92d2d' }}>*</span>
                        {selectedDue && <small>Outstanding: {formatMoney(Number(selectedDue.availableDue))}</small>}
                        <input required type="number" min="0.01" step="0.01" max={selectedDue ? Number(selectedDue.availableDue) : undefined} disabled={!selectedDue} value={line.amount}
                          onChange={e => setAdditionalAllocations(lines => lines.map((item, itemIndex) => itemIndex === index ? { ...item, amount: e.target.value } : item))}/>
                      </label>
                      <button type="button" className="secondary" onClick={() => setAdditionalAllocations(lines => lines.filter((_, itemIndex) => itemIndex !== index))}>Remove</button>
                    </div>;
                  })}
                </div>
              </>}

              {!entryOnly && <label>
                Payment Date <span style={{ color: '#b92d2d' }}>*</span>
                <input
                  required
                  type="date"
                  value={form.paymentDate}
                  onChange={e => setForm({ ...form, paymentDate: e.target.value })}
                />
              </label>}

              {entryOnly && <div className="receipt-entry-section wide"><h4>Payment Mode</h4><p>Select Cash or Bank. The relevant fields will appear based on your selection.</p></div>}

              <label>
                {entryOnly ? 'Receipt Type' : 'Payment Method'} <span style={{ color: '#b92d2d' }}>*</span>
                <select required value={form.method} onChange={e => handlePaymentMethodChange(e.target.value)}>
                  {entryOnly ? <><option value="CASH">Cash</option><option value="BANK_TRANSFER">Bank</option></> : <>
                    <option value="BANK_TRANSFER">Bank Deposit / Transfer</option>
                    <option value="CASH">Cash Site Collection</option>
                    <option value="ONLINE">Online Transfer</option>
                    <option value="CHEQUE">Cheque / Pay Order</option>
                  </>}
                </select>
              </label>

              {entryOnly && <label>
                Sales Classification <span style={{ color: '#b92d2d' }}>*</span>
                <select required value={form.salesClassification} onChange={e => setForm({ ...form, salesClassification: e.target.value })}>
                  <option value="">— Select Classification —</option>
                  <option value="Sales">Sales</option>
                  <option value="Charges">Charges</option>
                  <option value="Variation">Variation</option>
                </select>
              </label>}

              {entryOnly && <label>
                Payment Reference
                <input maxLength={120} value={form.paymentReference} onChange={e => setForm({ ...form, paymentReference: e.target.value })} placeholder="Optional payment reference" />
              </label>}

              {entryOnly && form.method !== 'CASH' && <label>
                Bank Instrument Type <span style={{ color: '#b92d2d' }}>*</span>
                <select required value={form.bankInstrumentType} onChange={e => setForm({ ...form, bankInstrumentType: e.target.value })}>
                  <option value="">— Select Instrument Type —</option>
                  <option value="Online Transfer">Online Transfer</option>
                  <option value="Cash">Cash</option>
                  <option value="Cheque">Cheque</option>
                  <option value="DD">DD</option>
                  <option value="PO">PO</option>
                </select>
              </label>}

              {(form.method === 'BANK_TRANSFER' || form.method === 'ONLINE' || form.method === 'CHEQUE') && (
                <>
                  <label>
                    District One Receiving Bank <span style={{ color: '#b92d2d' }}>*</span>
                    <select required value={form.bankId} onChange={e => setForm({ ...form, bankId: e.target.value })}>
                      <option value="">— Select Bank —</option>
                      {banks.map(b => (
                        <option key={String(b.id)} value={String(b.id)}>
                          {String(b.bankName)}{b.accountNo ? ` — A/C: ${String(b.accountNo)}` : ''}{b.branch ? ` (${String(b.branch)})` : ''}
                        </option>
                      ))}
                    </select>
                  </label>

                  <label>
                    Bank Document No. / Transaction ID / Reference <span style={{ color: '#b92d2d' }}>*</span>
                    <input
                      required
                      type="text"
                      maxLength={120}
                      placeholder="Bank document or transaction/reference number"
                      value={form.referenceNo}
                      onChange={e => setForm({ ...form, referenceNo: e.target.value })}
                    />
                  </label>

                  <label>
                    Deposit / Instrument Date <span style={{ color: '#b92d2d' }}>*</span>
                    <input
                      required
                      type="date"
                      value={form.depositDate}
                      onChange={e => setForm({ ...form, depositDate: e.target.value })}
                    />
                  </label>
                </>
              )}

              {form.method === 'CASH' && (
                <>
                <label>
                  Cash Collection Site <span style={{ color: '#b92d2d' }}>*</span>
                  <select required value={form.cashSiteId} onChange={e => setForm({ ...form, cashSiteId: e.target.value })}>
                    <option value="">— Select Collection Site (HO / Branch) —</option>
                    {cashSites.map(cs => (
                      <option key={String(cs.id)} value={String(cs.id)}>
                        {String(cs.siteName)}{cs.location ? ` — ${String(cs.location)}` : ''}
                      </option>
                    ))}
                  </select>
                </label>
                {entryOnly && <label>Reference No. (optional)<input maxLength={120} value={form.referenceNo} onChange={e => setForm({ ...form, referenceNo: e.target.value })}/></label>}
                </>
              )}

              <label className="wide">
                Upload Payment Evidence / Receipt Document
                <input
                  type="file"
                  accept="image/*,application/pdf"
                  onChange={e => setEvidenceFile(e.target.files && e.target.files[0] ? e.target.files[0] : null)}
                />
              </label>

              <label className="wide">
                Remarks / Collection Notes
                <textarea
                  placeholder="Additional collection remarks..."
                  value={form.remarks}
                  onChange={e => setForm({ ...form, remarks: e.target.value })}
                />
              </label>
            </div>

            {formError && <div className="error" style={{ marginTop: '15px' }}>{formError}</div>}

            <div className="modal-actions">
              {!entryOnly && <button type="button" className="secondary" onClick={() => setModalRecovery(false)}>Cancel</button>}
              <button className="primary" disabled={saving}>
                {saving ? 'Saving…' : entryOnly ? 'Save Receipt' : 'Record Recovery Receipt'}
              </button>
            </div>
          </form>
        </div>
      )}

      {/* Payment Detail Modal */}
      {detailPayment && (
        <div className="modal-backdrop">
          <div className="modal finance-modal receipt-printable">
            <div className="modal-head">
              <div>
                <p className="eyebrow">FINANCE RECEIPT / {detailPayment.receiptNo}</p>
                <h2>Receipt Details — {detailPayment.receiptNo}</h2>
              </div>
              <button type="button" className="icon" onClick={() => setDetailPayment(null)}><X /></button>
            </div>

            <div className="form-grid" style={{ marginTop: '15px' }}>
              <div><span>Member</span><br /><b>{detailPayment.memberName} ({detailPayment.mbrNo})</b></div>
              <div><span>Header Charge Head</span><br /><b>{detailPayment.chargeName || 'See allocation lines'}</b></div>
              <div><span>Payment Amount</span><br /><b style={{ fontSize: '18px', color: '#0d638e' }}>{formatMoney(Number(detailPayment.amount))}</b></div>
              <div><span>Payment Date</span><br /><b>{detailPayment.paymentDate ? String(detailPayment.paymentDate).slice(0, 10) : '—'}</b></div>
              {detailPayment.registrationNo && <div><span>Registration</span><br /><b>{detailPayment.registrationNo}</b></div>}
              {detailPayment.location && <div><span>Location</span><br /><b>{detailPayment.location}</b></div>}
              <div><span>Payment Method</span><br /><b>{detailPayment.method}</b></div>
              {detailPayment.salesClassification && <div><span>Sales Classification</span><br /><b>{detailPayment.salesClassification}</b></div>}
              {detailPayment.bankInstrumentType && <div><span>Bank Instrument Type</span><br /><b>{detailPayment.bankInstrumentType}</b></div>}
              <div><span>Status</span><br /><b className="badge">{detailPayment.status}</b></div>
              {detailPayment.bankName && <div><span>Receiving Bank</span><br /><b>{detailPayment.bankName}</b></div>}
              {detailPayment.paymentReference && <div><span>Payment Reference</span><br /><b>{detailPayment.paymentReference}</b></div>}
              {detailPayment.referenceNo && <div><span>Bank Document / Instrument No.</span><br /><b>{detailPayment.referenceNo}</b></div>}
              {detailPayment.cashSiteName && <div><span>Cash Site</span><br /><b>{detailPayment.cashSiteName}</b></div>}
              {detailPayment.enteredBy && <div><span>Entered By</span><br /><b>{detailPayment.enteredBy}</b></div>}
              {detailPayment.verifiedBy && <div><span>Verified By</span><br /><b>{detailPayment.verifiedBy}</b></div>}
              {detailPayment.verifiedAt && <div><span>Verification Date / Time</span><br /><b>{String(detailPayment.verifiedAt).replace('T', ' ')}</b></div>}
              {detailPayment.verifiedSourceAmount != null && <div><span>Verified Source Amount</span><br /><b>{formatMoney(Number(detailPayment.verifiedSourceAmount))}</b></div>}
              {detailPayment.verificationReference && <div><span>Verified Source Reference</span><br /><b>{detailPayment.verificationReference}</b></div>}
              {detailPayment.verificationRemarks && <div className="wide"><span>Verification Remarks</span><br /><p>{detailPayment.verificationRemarks}</p></div>}
              {detailPayment.rejectionReason && <div className="wide"><span style={{ color: '#8b2d2d' }}>Rejection Reason</span><br /><b style={{ color: '#8b2d2d' }}>{detailPayment.rejectionReason}</b></div>}
              {detailPayment.remarks && <div className="wide"><span>Remarks</span><br /><p>{detailPayment.remarks}</p></div>}
              {detailPayment.evidenceId && (
                <div className="wide">
                  <span>Attached Evidence File</span><br />
                  <a
                    href={`${API}/finance/documents/${detailPayment.evidenceId}/download`}
                    target="_blank"
                    rel="noreferrer"
                    className="primary compact"
                    style={{ marginTop: '6px', textDecoration: 'none', display: 'inline-flex', alignItems: 'center', gap: '6px' }}
                  >
                    <FileText size={14} /> Download / View {detailPayment.evidenceName || 'Evidence File'}
                  </a>
                </div>
              )}
            </div>

            <PaymentAllocationSummary rows={paymentAllocations} loading={allocationsLoading} error={allocationError} />

            <div className="modal-actions">
              <button className="primary" onClick={() => printReceiptDocument(detailPayment, paymentAllocations)}><ReceiptText size={14}/> Print Receipt</button>
              <button className="secondary" onClick={() => setDetailPayment(null)}>Close</button>
            </div>
          </div>
        </div>
      )}

      {verificationPayment && (
        <div className="modal-backdrop">
          <form className="modal finance-modal" onSubmit={submitVerification}>
            <div className="modal-head">
              <div>
                <p className="eyebrow">FINANCE / VERIFY PAYMENT SOURCE</p>
                <h2>Verify Receipt {verificationPayment.receiptNo}</h2>
                <p className="muted">Verification checks the payment source and plot allocations. It does not post the payment to the customer account.</p>
              </div>
              <button type="button" className="icon" onClick={() => setVerificationPayment(null)}><X /></button>
            </div>
            <div className="form-grid">
              <div><span>Customer / Member</span><br /><b>{verificationPayment.memberName} ({verificationPayment.mbrNo})</b></div>
              <div><span>Payment Method</span><br /><b>{verificationPayment.method}</b></div>
              {verificationPayment.salesClassification && <div><span>Sales Classification</span><br /><b>{verificationPayment.salesClassification}</b></div>}
              {verificationPayment.bankInstrumentType && <div><span>Bank Instrument Type</span><br /><b>{verificationPayment.bankInstrumentType}</b></div>}
              {verificationPayment.bankName && <div><span>Receiving Bank</span><br /><b>{verificationPayment.bankName}</b></div>}
              {verificationPayment.cashSiteName && <div><span>Cash Collection Site</span><br /><b>{verificationPayment.cashSiteName}</b></div>}
              {verificationPayment.paymentReference && <div><span>Payment Reference</span><br /><b>{verificationPayment.paymentReference}</b></div>}
              {verificationPayment.referenceNo && <div><span>Bank Document / Instrument No.</span><br /><b>{verificationPayment.referenceNo}</b></div>}
              {verificationPayment.depositDate && <div><span>Deposit / Instrument Date</span><br /><b>{String(verificationPayment.depositDate).slice(0, 10)}</b></div>}
              <div><span>Submitted Payment Amount</span><br /><b>{formatMoney(Number(verificationPayment.amount))}</b></div>
              <label>
                Verified Source Amount (PKR) <span style={{ color: '#b92d2d' }}>*</span>
                <input required type="number" min="0.01" step="0.01" value={verifiedSourceAmount}
                  onChange={e => setVerifiedSourceAmount(e.target.value)} />
              </label>
              {verificationPayment.method !== 'CASH' && (
                <label className="wide">
                  Verified Bank / Payment Source Reference <span style={{ color: '#b92d2d' }}>*</span>
                  <input required value={verifiedSourceReference} onChange={e => setVerifiedSourceReference(e.target.value)}
                    placeholder="Reference checked against the bank or payment source" />
                </label>
              )}
              <label className="wide">
                Verification Remarks <span style={{ color: '#b92d2d' }}>*</span>
                <textarea required value={verificationRemarks} onChange={e => setVerificationRemarks(e.target.value)}
                  placeholder="Record what was checked against the payment source..." />
              </label>
              {verificationPayment.evidenceId && (
                <div className="wide"><a href={`${API}/finance/documents/${verificationPayment.evidenceId}/download`} target="_blank" rel="noreferrer">
                  <FileText size={14} /> View submitted payment evidence
                </a></div>
              )}
            </div>
            <PaymentAllocationSummary rows={paymentAllocations} loading={allocationsLoading} error={allocationError} />
            <div className="modal-actions">
              <button type="button" className="secondary" onClick={() => setVerificationPayment(null)}>Cancel</button>
              <button className="primary" disabled={verifying}>{verifying ? 'Verifying...' : 'Confirm Finance Verification'}</button>
            </div>
          </form>
        </div>
      )}

      {/* Reject Payment Modal */}
      {rejectPaymentItem && (
        <div className="modal-backdrop">
          <form className="modal" onSubmit={submitRejection}>
            <div className="modal-head">
              <div>
                <p className="eyebrow">FINANCE / REJECT PAYMENT</p>
                <h2>Reject Receipt {rejectPaymentItem.receiptNo}</h2>
              </div>
              <button type="button" className="icon" onClick={() => setRejectPaymentItem(null)}><X /></button>
            </div>

            <div className="form-grid">
              <label className="wide">
                Mandatory Rejection Reason <span style={{ color: '#b92d2d' }}>*</span>
                <textarea
                  required
                  placeholder="Specify why this recovery entry is rejected..."
                  value={rejectReason}
                  onChange={e => setRejectReason(e.target.value)}
                />
              </label>
            </div>

            <div className="modal-actions">
              <button type="button" className="secondary" onClick={() => setRejectPaymentItem(null)}>Cancel</button>
              <button className="primary" disabled={rejecting} style={{ background: '#8b2d2d' }}>
                {rejecting ? 'Rejecting...' : 'Confirm Rejection'}
              </button>
            </div>
          </form>
        </div>
      )}
    </>
  );
}

function Finance() {
  const [tab, setTab] = useState('Payments & Recovery');
  const tabs = ['Payments & Recovery', 'Receipt Entry', 'Customer Ledger', 'Receivables / Outstanding',
    'Chart of Accounts', 'Finance Reports', 'Adjustments & Reversals'].filter(item => {
    if (item === 'Chart of Accounts') return canPerformAction('Finance', 'VIEW') && isSessionSuperAdmin();
    if (item === 'Finance Reports') return isSessionSuperAdmin();
    return canPerformAction('Finance', 'VIEW');
  });
  const currentTab = tabs.includes(tab) ? tab : tabs[0] ?? '';

  return (
    <>
      <div className="toolbar">
        <div>
          <p className="eyebrow">PAYMENTS, RECOVERY & LEDGER</p>
          <h1>Finance Workspace</h1>
          <p className="muted">Record recovery collections, verify payments, manage ledger and chart of accounts.</p>
        </div>
      </div>
      <div className="finance-nav-grid">
        {tabs.map(item => (
          <button
            key={item}
            className={tab === item ? 'finance-nav active' : 'finance-nav'}
            onClick={() => setTab(item)}
          >
            <WalletCards size={16} />
            <span>{item}</span>
          </button>
        ))}
      </div>
      {currentTab ? <FinanceSubworkspace tab={currentTab} onNavigate={setTab} />
        : <div className="empty-page"><h1>No Finance view access</h1><p className="muted">Ask an administrator to review your Finance permissions.</p></div>}
    </>
  );
}

type AccountItem = {
  id: number;
  accountCode: string;
  accountName: string;
  accountType: string;
  parentId?: number | null;
  parentCode?: string | null;
  parentName?: string | null;
  description?: string | null;
  normalBalance: 'DEBIT' | 'CREDIT';
  displayOrder: number;
  active: boolean;
};

const ACCOUNT_TYPES = [
  'ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE', 'RECEIVABLE', 'CASH', 'BANK'
];

function ChartOfAccountsWorkspace() {
  const [accounts, setAccounts] = useState<AccountItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState('ALL');

  const [modal, setModal] = useState(false);
  const [editingAccount, setEditingAccount] = useState<AccountItem | null>(null);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');

  const [form, setForm] = useState({
    accountCode: '',
    accountName: '',
    accountType: 'ASSET',
    parentId: '',
    description: '',
    normalBalance: 'DEBIT',
    displayOrder: '0',
    active: true
  });

  const load = () => {
    setLoading(true);
    setError('');
    api<AccountItem[]>('/finance/accounts')
      .then(setAccounts)
      .catch(err => setError(err instanceof Error ? err.message : 'Failed to load Chart of Accounts.'))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  function handleTypeChange(selectedType: string) {
    const defaultBalance = ['ASSET', 'EXPENSE', 'RECEIVABLE', 'CASH', 'BANK'].includes(selectedType) ? 'DEBIT' : 'CREDIT';
    setForm(current => ({
      ...current,
      accountType: selectedType,
      normalBalance: defaultBalance
    }));
  }

  function openCreate() {
    setEditingAccount(null);
    setForm({
      accountCode: '',
      accountName: '',
      accountType: 'ASSET',
      parentId: '',
      description: '',
      normalBalance: 'DEBIT',
      displayOrder: '0',
      active: true
    });
    setFormError('');
    setModal(true);
  }

  function openEdit(acc: AccountItem) {
    setEditingAccount(acc);
    setForm({
      accountCode: acc.accountCode || '',
      accountName: acc.accountName || '',
      accountType: acc.accountType || 'ASSET',
      parentId: acc.parentId ? String(acc.parentId) : '',
      description: acc.description || '',
      normalBalance: acc.normalBalance || 'DEBIT',
      displayOrder: String(acc.displayOrder ?? 0),
      active: acc.active
    });
    setFormError('');
    setModal(true);
  }

  async function submitForm(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setFormError('');

    try {
      const payload = {
        accountCode: form.accountCode.trim(),
        accountName: form.accountName.trim(),
        accountType: form.accountType,
        parentId: form.parentId ? Number(form.parentId) : null,
        description: form.description.trim(),
        normalBalance: form.normalBalance,
        displayOrder: Number(form.displayOrder) || 0,
        active: form.active
      };

      if (editingAccount) {
        await api(`/finance/accounts/${editingAccount.id}`, {
          method: 'PUT',
          body: JSON.stringify(payload)
        });
      } else {
        await api('/finance/accounts', {
          method: 'POST',
          body: JSON.stringify(payload)
        });
      }

      setModal(false);
      load();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Unable to save account.');
    } finally {
      setSaving(false);
    }
  }

  async function toggleStatus(acc: AccountItem) {
    try {
      await api(`/finance/accounts/${acc.id}/status`, {
        method: 'PATCH',
        body: JSON.stringify({ active: !acc.active })
      });
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update status.');
    }
  }

  async function deleteAccount(acc: AccountItem) {
    if (!window.confirm(`Are you sure you want to delete account "${acc.accountCode} - ${acc.accountName}"?`)) return;
    try {
      await api(`/finance/accounts/${acc.id}`, { method: 'DELETE' });
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete account.');
    }
  }

  const filtered = accounts.filter(acc => {
    const matchesSearch = !search ||
      acc.accountCode.toLowerCase().includes(search.toLowerCase()) ||
      acc.accountName.toLowerCase().includes(search.toLowerCase()) ||
      (acc.parentName && acc.parentName.toLowerCase().includes(search.toLowerCase()));
    const matchesType = typeFilter === 'ALL' || acc.accountType === typeFilter;
    return matchesSearch && matchesType;
  });

  return (
    <div className="panel finance-subpanel">
      <div className="panel-head">
        <div>
          <p className="eyebrow">FINANCE / CHART OF ACCOUNTS</p>
          <h3>Account Hierarchy & Structure</h3>
        </div>
        <div className="finance-filter">
          <label>
            <span>Type Filter</span>
            <select value={typeFilter} onChange={e => setTypeFilter(e.target.value)}>
              <option value="ALL">All Types</option>
              {ACCOUNT_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
            </select>
          </label>
          <button className="secondary" onClick={load}><RefreshCw size={15} /> Refresh</button>
          <button className="primary" onClick={openCreate}><Plus size={15} /> New Account</button>
        </div>
      </div>

      {error && <div className="error page-error" style={{ marginTop: '15px' }}>{error}</div>}

      <div className="search" style={{ marginTop: '15px', marginBottom: '15px' }}>
        <Search size={18} />
        <input
          placeholder="Search accounts by code, name or parent account..."
          value={search}
          onChange={e => setSearch(e.target.value)}
        />
      </div>

      {loading ? (
        <div className="empty">Loading Chart of Accounts...</div>
      ) : filtered.length === 0 ? (
        <div className="empty">No accounts match the criteria. Click "New Account" to create one.</div>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Code</th>
                <th>Account Name</th>
                <th>Type</th>
                <th>Parent Account</th>
                <th>Normal Balance</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(acc => (
                <tr key={acc.id}>
                  <td className="code">{acc.accountCode}</td>
                  <td>
                    <b style={{ paddingLeft: acc.parentId ? '14px' : '0' }}>
                      {acc.parentId ? '└ ' : ''}{acc.accountName}
                    </b>
                    {acc.description && <small>{acc.description}</small>}
                  </td>
                  <td>
                    <span className="badge" style={{ background: '#eaf4f8', color: '#0f5477' }}>
                      {acc.accountType}
                    </span>
                  </td>
                  <td>
                    {acc.parentCode ? (
                      <span><b>{acc.parentCode}</b> - {acc.parentName}</span>
                    ) : (
                      <span className="muted" style={{ fontStyle: 'italic', fontSize: '11px' }}>Top Level</span>
                    )}
                  </td>
                  <td>
                    <span className={acc.normalBalance === 'DEBIT' ? 'status-badge success' : 'status-badge neutral'}>
                      {acc.normalBalance}
                    </span>
                  </td>
                  <td>
                    <span className={acc.active ? 'status-badge success' : 'status-badge danger'}>
                      {acc.active ? 'ACTIVE' : 'INACTIVE'}
                    </span>
                  </td>
                  <td>
                    <button className="secondary compact" onClick={() => openEdit(acc)} style={{ marginRight: '6px' }}>
                      <Pencil size={13} /> Edit
                    </button>
                    <button className="secondary compact" onClick={() => toggleStatus(acc)} style={{ marginRight: '6px' }}>
                      {acc.active ? 'Deactivate' : 'Activate'}
                    </button>
                    <button className="secondary compact" onClick={() => deleteAccount(acc)} style={{ color: '#8b2d2d', borderColor: '#f2d2d2' }}>
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {modal && (
        <div className="modal-backdrop">
          <form className="modal finance-modal" onSubmit={submitForm}>
            <div className="modal-head">
              <div>
                <p className="eyebrow">CHART OF ACCOUNTS</p>
                <h2>{editingAccount ? `Edit Account (${editingAccount.accountCode})` : 'Create New Account'}</h2>
              </div>
              <button type="button" className="icon" onClick={() => setModal(false)}><X /></button>
            </div>

            <div className="form-grid">
              <label>
                Account Code <small style={{ color: '#7792a2', fontWeight: 'normal' }}>(Leave blank to auto-generate)</small>
                <input
                  type="text"
                  placeholder="e.g. 1010 or leave blank"
                  value={form.accountCode}
                  onChange={e => setForm({ ...form, accountCode: e.target.value })}
                />
              </label>

              <label>
                Account Name <span style={{ color: '#b92d2d' }}>*</span>
                <input
                  required
                  type="text"
                  placeholder="e.g. Cash in Hand"
                  value={form.accountName}
                  onChange={e => setForm({ ...form, accountName: e.target.value })}
                />
              </label>

              <label>
                Account Type <span style={{ color: '#b92d2d' }}>*</span>
                <select
                  value={form.accountType}
                  onChange={e => handleTypeChange(e.target.value)}
                >
                  {ACCOUNT_TYPES.map(t => (
                    <option key={t} value={t}>{t}</option>
                  ))}
                </select>
              </label>

              <label>
                Parent Account
                <select
                  value={form.parentId}
                  onChange={e => setForm({ ...form, parentId: e.target.value })}
                >
                  <option value="">— None (Top Level Control Account) —</option>
                  {accounts
                    .filter(a => !editingAccount || a.id !== editingAccount.id)
                    .map(a => (
                      <option key={a.id} value={String(a.id)}>
                        {a.accountCode} - {a.accountName} ({a.accountType})
                      </option>
                    ))}
                </select>
              </label>

              <label>
                Normal Balance
                <select
                  value={form.normalBalance}
                  onChange={e => setForm({ ...form, normalBalance: e.target.value })}
                >
                  <option value="DEBIT">DEBIT</option>
                  <option value="CREDIT">CREDIT</option>
                </select>
              </label>

              <label>
                Display Order
                <input
                  type="number"
                  value={form.displayOrder}
                  onChange={e => setForm({ ...form, displayOrder: e.target.value })}
                />
              </label>

              <label className="wide">
                Description
                <textarea
                  placeholder="Account purpose or notes..."
                  value={form.description}
                  onChange={e => setForm({ ...form, description: e.target.value })}
                />
              </label>

              <label className="wide status-check">
                <input
                  type="checkbox"
                  checked={form.active}
                  onChange={e => setForm({ ...form, active: e.target.checked })}
                />
                Active Account
              </label>
            </div>

            {formError && <div className="error" style={{ marginTop: '16px' }}>{formError}</div>}

            <div className="modal-actions">
              <button type="button" className="secondary" onClick={() => setModal(false)}>
                Cancel
              </button>
              <button className="primary" disabled={saving}>
                {saving ? 'Saving...' : editingAccount ? 'Update Account' : 'Create Account'}
              </button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}

function FinanceReportsWorkspace(){const [report,setReport]=useState<Record<string,unknown>|null>(null);const [error,setError]=useState('');useEffect(()=>{api<Record<string,unknown>>('/reports/summary').then(setReport).catch(e=>setError(e.message))},[]);return <div className="panel finance-subpanel"><div className="panel-head"><div><p className="eyebrow">FINANCE REPORTS</p><h3>Operational Finance Summary</h3></div><button className="secondary" onClick={()=>window.print()}><FileText size={15}/> Print / PDF</button></div>{error&&<div className="error page-error">{error}</div>}<div className="stat-grid" style={{marginTop:'18px'}}>{report&&Object.entries(report).map(([key,value])=><div className="stat" key={key}><span>{key.replace(/([A-Z])/g,' $1')}</span><strong>{String(value)}</strong><small>Current database count</small></div>)}</div><div className="empty">Use Payments & Recovery and Customer Ledger for transaction-level review. This report is read-only.</div></div>}
function FinanceAdjustmentsWorkspace(){const [rows,setRows]=useState<Record<string,unknown>[]>([]);const [members,setMembers]=useState<Customer[]>([]);const [receivables,setReceivables]=useState<Record<string,unknown>[]>([]);const [open,setOpen]=useState(false);const [error,setError]=useState('');const [saving,setSaving]=useState(false);const blank={memberId:'',receivableId:'',amount:'',direction:'CREDIT',reason:''};const [form,setForm]=useState(blank);const load=()=>Promise.all([api<Record<string,unknown>[]>('/finance/receivables'),api<Customer[]>('/customers'),api<Record<string,unknown>[]>('/audit')]).then(([r,m,a])=>{setReceivables(r);setMembers(m);setRows(a.filter(x=>String(x.module||'').includes('FINANCE')))}).catch(e=>setError(e.message));useEffect(() => { load(); }, []);async function save(e:React.FormEvent){e.preventDefault();setSaving(true);try{await api('/finance/adjustments',{method:'POST',body:JSON.stringify({memberId:Number(form.memberId),receivableId:form.receivableId?Number(form.receivableId):null,amount:Number(form.amount),direction:form.direction,reason:form.reason})});setOpen(false);setForm(blank);load()}catch(e){setError(e instanceof Error?e.message:'Unable to save adjustment.')}finally{setSaving(false)}}return <div className="config-workspace"><div className="panel"><div className="panel-head"><div><p className="eyebrow">CONTROLLED ADJUSTMENTS</p><h3>Finance Adjustments</h3></div><button className="primary" onClick={()=>setOpen(true)}><Plus size={15}/> New Adjustment</button></div>{error&&<div className="error page-error">{error}</div>}<div className="empty">Adjustments are posted as controlled records and require Finance or Administrator authorization.</div></div><div className="panel"><div className="panel-head"><h3>Finance audit activity</h3><span className="muted">{rows.length} record(s)</span></div><div className="table-wrap"><table><thead><tr><th>Time</th><th>User</th><th>Action</th><th>Details</th></tr></thead><tbody>{rows.map((r,i)=><tr key={i}><td>{String(r.occurredAt||'').replace('T',' ').slice(0,19)}</td><td>{String(r.username||'—')}</td><td>{String(r.action||'—')}</td><td>{String(r.details||'—')}</td></tr>)}</tbody></table></div></div>{open&&<div className="modal-backdrop"><form className="modal" onSubmit={save}><div className="modal-head"><div><p className="eyebrow">FINANCE ADJUSTMENT</p><h2>New Adjustment</h2></div><button type="button" className="icon" onClick={()=>setOpen(false)}><X/></button></div><div className="form-grid"><label>Member<select required value={form.memberId} onChange={e=>setForm({...form,memberId:e.target.value,receivableId:''})}><option value="">Select member</option>{members.map(m=><option key={m.id} value={m.id}>{m.name} — {m.mbrNo}</option>)}</select></label><label>Direction<select value={form.direction} onChange={e=>setForm({...form,direction:e.target.value})}><option>CREDIT</option><option>DEBIT</option></select></label><label className="wide">Receivable<select value={form.receivableId} onChange={e=>setForm({...form,receivableId:e.target.value})}><option value="">General member adjustment</option>{receivables.filter(r=>!form.memberId||String(r.memberId)===form.memberId).map((r,i)=><option key={String(r.id??i)} value={String(r.id??'')}>{String(r.mbrNo||'')} — {String(r.description||'')} — {formatMoney(Number(r.outstanding||0))}</option>)}</select></label><label>Amount<input required min="0.01" step="0.01" type="number" value={form.amount} onChange={e=>setForm({...form,amount:e.target.value})}/></label><label className="wide">Reason<input required value={form.reason} onChange={e=>setForm({...form,reason:e.target.value})}/></label></div><div className="modal-actions"><button type="button" className="secondary" onClick={()=>setOpen(false)}>Cancel</button><button className="primary" disabled={saving}>{saving?'Saving…':'Submit Adjustment'}</button></div></form></div>}</div>}

function FinanceSubworkspace({ tab, onNavigate }: { tab: string; onNavigate: (tab: string) => void }) {
  if (tab === 'Receipt Entry') return <PaymentsRecoveryWorkspace entryOnly onBack={() => onNavigate('Payments & Recovery')} />;
  if (tab === 'Payments & Recovery') return <PaymentsRecoveryWorkspace onNavigate={onNavigate} />;
  if (tab === 'Chart of Accounts') return <ChartOfAccountsWorkspace />;
  if (tab === 'Customer Ledger') return <CustomerLedgerWorkspace />;
  if (tab === 'Finance Reports') return <FinanceReportsWorkspace />;
  if (tab === 'Adjustments & Reversals') return <FinanceAdjustmentsWorkspace />;
  return <FinanceDataWorkspace tab={tab} />;
}

function FinanceDataWorkspace({ tab }: { tab: string }) {
  const [rows, setRows] = useState<Record<string, unknown>[]>([]); const [loading, setLoading] = useState(true); const [error, setError] = useState('');
  const endpoint = tab === 'Receivables / Outstanding' || tab === 'Charges & Dues' ? '/finance/receivables' : null;
  useEffect(() => { if (!endpoint) { setLoading(false); return; } setLoading(true); api<Record<string, unknown>[]>(endpoint).then(setRows).catch(e => setError(e.message)).finally(() => setLoading(false)); }, [endpoint]);
  return <div className="panel finance-subpanel"><div className="panel-head"><div><p className="eyebrow">{tab.toUpperCase()}</p><h3>{tab}</h3></div><button className="secondary" onClick={() => endpoint && api<Record<string, unknown>[]>(endpoint).then(setRows)}>Refresh</button></div>{error && <div className="error">{error}</div>}{loading ? <div className="empty">Loading finance data…</div> : rows.length === 0 ? <div className="empty">No records found.</div> : <div className="table-wrap"><table><thead><tr>{Object.keys(rows[0]).map(key => <th key={key}>{key.replace(/([A-Z])/g, ' $1')}</th>)}</tr></thead><tbody>{rows.map((row, index) => <tr key={index}>{Object.values(row).map((value, cell) => <td key={cell}>{String(value ?? '—')}</td>)}</tr>)}</tbody></table></div>}</div>;
}

function CareWorkspace() {
  const [tab,setTab]=useState('Overview');
  const tabs=['Overview','Customer Lookup','Complaints','Enquiries','Service Tickets','Follow-ups','Feedback','Announcements','Mobile Content'];
  return <><div className="toolbar"><div><p className="eyebrow">CUSTOMER RELATIONS</p><h1>Customer Care</h1><p className="muted">Review customer records and manage complaints, enquiries, service tickets, follow-ups, feedback, announcements, and mobile app content.</p></div></div><div className="finance-nav-grid sales-nav-grid">{tabs.map(item=><button key={item} className={tab===item?'finance-nav active':'finance-nav'} onClick={()=>setTab(item)}><Headphones size={16}/><span>{item}</span></button>)}</div>{tab==='Overview'&&<CareOverview/>}{tab==='Customer Lookup'&&<CareCustomerLookup/>}{tab==='Complaints'&&<CareComplaints/>}{tab==='Enquiries'&&<CareEnquiries/>}{tab==='Service Tickets'&&<CareTickets/>}{tab==='Follow-ups'&&<CareFollowups/>}{tab==='Feedback'&&<CareFeedback/>}{tab==='Announcements'&&<CareAnnouncements/>}{tab==='Mobile Content'&&<CareMobileContent/>}</>;
}

type MobileContentRow = {id:number;title:string;body?:string;location?:string;status?:string;date?:string;image?:string;phone?:string;email?:string;sortOrder?:number;active?:boolean};
const mobileContentSections = [{key:'projects',label:'Projects'},{key:'news',label:'News'},{key:'events',label:'Events'},{key:'about',label:'About Us'},{key:'contact',label:'Contact Us'}];
function CareMobileContent(){
  const [section,setSection]=useState('projects');const [rows,setRows]=useState<MobileContentRow[]>([]);const [editing,setEditing]=useState<MobileContentRow|null>(null);const [open,setOpen]=useState(false);const [loading,setLoading]=useState(false);const [saving,setSaving]=useState(false);const [error,setError]=useState('');
  const page=section==='about'||section==='contact';const blank:MobileContentRow={id:0,title:'',body:'',location:'',status:'',date:'',image:'',phone:section==='contact'?'+92 325 1663663':'',email:'',sortOrder:0,active:true};const [form,setForm]=useState<MobileContentRow>(blank);
  const load=()=>{setLoading(true);setError('');api<MobileContentRow[]>(`/customer-care/mobile-content/${section}`).then(data=>{setRows(data);if(page&&data.length===0)setError('The page has no saved content. Save it to publish its current details.');}).catch(e=>setError(e.message)).finally(()=>setLoading(false));};
  useEffect(()=>{setForm({...blank});load();},[section]);
  function startEdit(row?:MobileContentRow){const next=row?{...blank,...row}: {...blank};setEditing(row||null);setForm(next);setOpen(true);}
  async function save(e:React.FormEvent){e.preventDefault();setSaving(true);setError('');try{const body=JSON.stringify({...form,active:form.active!==false,sortOrder:Number(form.sortOrder||0)});await api(`/customer-care/mobile-content/${section}${editing?`/${editing.id}`:''}`,{method:editing?'PUT':'POST',body});setOpen(false);setEditing(null);load();}catch(e){setError(e instanceof Error?e.message:'Unable to save mobile content.')}finally{setSaving(false)}}
  async function remove(row:MobileContentRow){if(!window.confirm(`Delete “${row.title}” from the mobile app?`))return;try{await api(`/customer-care/mobile-content/${section}/${row.id}`,{method:'DELETE'});load()}catch(e){setError(e instanceof Error?e.message:'Unable to delete content.')}}
  const selected=mobileContentSections.find(item=>item.key===section)!;
  return <div className="config-workspace"><div className="panel"><div className="panel-head"><div><p className="eyebrow">DISTRICT ONE MOBILE APP</p><h3>Public Content</h3><p className="muted">Manage what visitors see in Projects, News, Events, About Us and Contact Us.</p></div></div><div className="finance-nav-grid sales-nav-grid">{mobileContentSections.map(item=><button key={item.key} className={section===item.key?'finance-nav active':'finance-nav'} onClick={()=>setSection(item.key)}><Headphones size={16}/><span>{item.label}</span></button>)}</div></div><div className="panel"><div className="panel-head"><div><p className="eyebrow">{selected.label.toUpperCase()}</p><h3>{page?'Mobile page details':`${selected.label} content`}</h3></div><button className="primary" onClick={()=>startEdit(page?rows[0]:undefined)}><Plus size={15}/>{page?(rows.length?'Edit page':'Set up page'):`Add ${selected.label.slice(0,-1)}`}</button></div>{error&&<div className="error page-error">{error}</div>}{loading?<div className="empty">Loading mobile content…</div>:rows.length===0?<div className="empty">No {selected.label.toLowerCase()} content has been published yet.</div>:<div className="table-wrap"><table><thead><tr><th>Title</th>{!page&&<><th>Details</th><th>Date / status</th></>}<th>Visibility</th><th>Actions</th></tr></thead><tbody>{rows.map(row=><tr key={row.id}><td><b>{row.title}</b>{section==='contact'&&row.phone&&<small>{row.phone}</small>}</td>{!page&&<><td>{row.body||'—'}<small>{row.location||''}</small></td><td>{row.date||'—'}<small>{row.status||''}</small></td></>}<td><span className="badge">{row.active===false?'Hidden':'Published'}</span></td><td><button className="secondary compact" onClick={()=>startEdit(row)}><Pencil size={13}/> Edit</button>{!page&&<> <button className="secondary compact" onClick={()=>void remove(row)}><X size={13}/> Delete</button></>}</td></tr>)}</tbody></table></div>}</div>{open&&<div className="modal-backdrop"><form className="modal" onSubmit={save}><div className="modal-head"><div><p className="eyebrow">MOBILE APP / {selected.label.toUpperCase()}</p><h2>{editing?'Edit':'Update'} {selected.label}</h2></div><button type="button" className="icon" onClick={()=>setOpen(false)}><X/></button></div><div className="form-grid"><label className="wide">Title<input required maxLength={255} value={form.title} onChange={e=>setForm({...form,title:e.target.value})}/></label><label className="wide">Description / Content<textarea value={form.body||''} onChange={e=>setForm({...form,body:e.target.value})}/></label>{(section==='projects'||section==='events')&&<label>Location / Venue<input value={form.location||''} onChange={e=>setForm({...form,location:e.target.value})}/></label>}{(section==='projects'||section==='events')&&<label>Status<input value={form.status||''} onChange={e=>setForm({...form,status:e.target.value})}/></label>}{(section==='news'||section==='events')&&<label>Date<input value={form.date||''} placeholder="e.g. 24 Oct 2026" onChange={e=>setForm({...form,date:e.target.value})}/></label>}{section!=='about'&&<label className="wide">Image URL (optional)<input value={form.image||''} onChange={e=>setForm({...form,image:e.target.value})}/></label>}{section==='contact'&&<><label>Contact number<input required value={form.phone||''} onChange={e=>setForm({...form,phone:e.target.value})}/></label><label>Email<input value={form.email||''} onChange={e=>setForm({...form,email:e.target.value})}/></label></>}{!page&&<label>Display order<input type="number" value={form.sortOrder||0} onChange={e=>setForm({...form,sortOrder:Number(e.target.value)})}/></label>}<label>Visible in mobile app<select value={form.active===false?'false':'true'} onChange={e=>setForm({...form,active:e.target.value==='true'})}><option value="true">Yes</option><option value="false">No</option></select></label></div>{section==='contact'&&<p className="muted">The contact number is shown on the mobile Contact Us page.</p>}{error&&<div className="error">{error}</div>}<div className="modal-actions"><button type="button" className="secondary" onClick={()=>setOpen(false)}>Cancel</button><button className="primary" disabled={saving}>{saving?'Saving…':'Save and publish'}</button></div></form></div>}</div>
}
type ComplaintRecord = {
  complaintId:number; subject:string; description?:string; status:string; createdAt:string;
  customer:{memberId:number;memberNo:string;name:string;contactNo?:string};
  historyCount?:number;
  saleReferences?:{registrationId:number;registrationNo?:string;bookingId?:number;bookingStatus?:string;plotId?:number;plotNo?:string;blockName?:string;registrationStatus?:string;plotStatus?:string}[];
  history?:{historyId:number;note:string;createdAt:string;recordedBy?:string}[];
};
type ComplaintPage={items:ComplaintRecord[];page:number;pageSize:number;totalElements:number};
function CareComplaints(){
  const [rows,setRows]=useState<ComplaintRecord[]>([]);
  const [members,setMembers]=useState<Customer[]>([]);
  const [query,setQuery]=useState('');
  const [memberSearch,setMemberSearch]=useState('');
  const [open,setOpen]=useState(false);
  const [selected,setSelected]=useState<ComplaintRecord|null>(null);
  const [note,setNote]=useState('');
  const [error,setError]=useState('');
  const [saving,setSaving]=useState(false);
  const [loading,setLoading]=useState(false);
  const [detailLoading,setDetailLoading]=useState(false);
  const blank={memberId:'',subject:'',description:''};
  const [form,setForm]=useState(blank);
  async function load(search=query){
    setLoading(true);setError('');
    try{const result=await api<ComplaintPage>(`/customer-care/complaints?page=0&pageSize=50${search.trim()?`&q=${encodeURIComponent(search.trim())}`:''}`);setRows(result.items);}
    catch(err){setError(err instanceof Error?err.message:'Unable to load complaints.');}
    finally{setLoading(false);}
  }
  useEffect(()=>{void load('');},[]);
  async function saveComplaint(e:React.FormEvent){
    e.preventDefault();setSaving(true);setError('');
    try{
      await api('/customer-care/complaints',{method:'POST',body:JSON.stringify({...form,memberId:Number(form.memberId),description:form.description.trim()||null})});
      setOpen(false);setForm(blank);await load();
    }catch(err){setError(err instanceof Error?err.message:'Unable to register complaint.');}
    finally{setSaving(false);}
  }
  async function searchMemberChoices(){
    if(!memberSearch.trim()){setError('Enter a customer lookup value before searching.');return;}
    setError('');
    try{setMembers(await api<Customer[]>(`/customer-care/customers?q=${encodeURIComponent(memberSearch.trim())}`));setForm(current=>({...current,memberId:''}));}
    catch(err){setError(err instanceof Error?err.message:'Unable to search customer choices.');}
  }
  async function viewComplaint(id:number){
    setDetailLoading(true);setError('');
    try{
      setSelected(await api<ComplaintRecord>(`/customer-care/complaints/${id}`));setNote('');
    }catch(err){setError(err instanceof Error?err.message:'Unable to load complaint details.');}
    finally{setDetailLoading(false);}
  }
  async function addNote(e:React.FormEvent){
    e.preventDefault();if(!selected||!note.trim())return;
    setSaving(true);setError('');
    try{
      await api(`/customer-care/complaints/${selected.complaintId}/history`,{method:'POST',body:JSON.stringify({note:note.trim()})});
      setSelected(await api<ComplaintRecord>(`/customer-care/complaints/${selected.complaintId}`));setNote('');await load();
    }catch(err){setError(err instanceof Error?err.message:'Unable to add complaint history note.');}
    finally{setSaving(false);}
  }
  return <div className="config-workspace">
    <div className="panel">
      <div className="panel-head"><div><p className="eyebrow">COMPLAINT REGISTER</p><h3>Customer complaints</h3></div><button className="primary" onClick={()=>setOpen(true)}><Plus size={15}/> Register Complaint</button></div>
      <form className="search" onSubmit={e=>{e.preventDefault();void load();}}><Search size={18}/><input aria-label="Search complaints" placeholder="Complaint, customer, member number, contact, or case ID" value={query} onChange={e=>setQuery(e.target.value)}/><button className="secondary" disabled={loading}>{loading?'Searching…':'Search'}</button></form>
      <p className="muted">Complaint records use the existing Customer Care case register and its OPEN default. The current schema has no complaint category or structured sale/plot link; customer sale references can be reviewed in the profile.</p>
      {error&&<div className="error page-error">{error}</div>}
      {loading?<div className="empty">Loading complaints…</div>:<div className="table-wrap"><table><thead><tr><th>Case</th><th>Customer</th><th>Subject / details</th><th>Date</th><th>Status</th><th>History</th><th></th></tr></thead><tbody>{rows.map(row=><tr key={row.complaintId}><td className="code">#{row.complaintId}</td><td>{row.customer.name}<small>{row.customer.memberNo}{row.customer.contactNo?` · ${row.customer.contactNo}`:''}</small></td><td><b>{row.subject}</b><small>{row.description||'—'}</small></td><td>{String(row.createdAt||'').replace('T',' ').slice(0,19)}</td><td><span className="badge">{row.status}</span></td><td>{row.historyCount||0}</td><td><button className="secondary compact" onClick={()=>void viewComplaint(row.complaintId)}>View</button></td></tr>)}</tbody></table>{rows.length===0&&<div className="empty">No complaints found.</div>}</div>}
    </div>
    {detailLoading&&<div className="panel"><div className="empty">Loading complaint details…</div></div>}
    {selected&&<div className="panel">
      <div className="panel-head"><div><p className="eyebrow">COMPLAINT CASE #{selected.complaintId}</p><h3>{selected.subject}</h3></div><button className="secondary" onClick={()=>setSelected(null)}>Close</button></div>
      <div className="stat-grid"><div className="stat"><span>Customer</span><strong style={{fontSize:'15px'}}>{selected.customer.name} · {selected.customer.memberNo}</strong></div><div className="stat"><span>Contact</span><strong style={{fontSize:'15px'}}>{selected.customer.contactNo||'—'}</strong></div><div className="stat"><span>Registered</span><strong style={{fontSize:'15px'}}>{String(selected.createdAt||'').replace('T',' ').slice(0,19)}</strong></div><div className="stat"><span>Status</span><strong style={{fontSize:'15px'}}>{selected.status}</strong></div></div>
      <p>{selected.description||'No additional complaint details.'}</p>
      <div className="panel-head" style={{marginTop:'18px'}}><div><p className="eyebrow">CUSTOMER SALE CONTEXT</p><h3>Registrations / bookings / plots</h3></div></div>
      <div className="table-wrap"><table><thead><tr><th>Registration</th><th>Booking</th><th>Plot</th><th>Statuses</th></tr></thead><tbody>{selected.saleReferences?.map((item,index)=><tr key={`${item.registrationId}-${item.bookingId||'none'}-${index}`}><td>{item.registrationNo||`#${item.registrationId}`}</td><td>{item.bookingId?`#${item.bookingId}`:'—'}</td><td>{item.plotNo||'—'}{item.blockName?` · ${item.blockName}`:''}</td><td>{[item.registrationStatus,item.bookingStatus,item.plotStatus].filter(Boolean).join(' / ')||'—'}</td></tr>)}</tbody></table>{selected.saleReferences?.length===0&&<div className="empty">No registration or booking records linked to this customer.</div>}</div>
      <div className="panel-head" style={{marginTop:'18px'}}><div><p className="eyebrow">CASE HISTORY</p><h3>Complaint updates</h3></div></div>
      <div className="table-wrap"><table><thead><tr><th>Date</th><th>Recorded by</th><th>Update</th></tr></thead><tbody>{(selected.history||[]).map(item=><tr key={item.historyId}><td>{String(item.createdAt||'').replace('T',' ').slice(0,19)}</td><td>{item.recordedBy||'—'}</td><td>{item.note}</td></tr>)}</tbody></table>{(selected.history||[]).length===0&&<div className="empty">No complaint history updates have been recorded.</div>}</div>
      <form className="form-grid" onSubmit={addNote} style={{marginTop:'16px'}}><label className="wide">Add complaint update<textarea required maxLength={5000} value={note} onChange={e=>setNote(e.target.value)}/></label><div className="wide"><button className="primary" disabled={saving||!note.trim()}>{saving?'Saving…':'Add Update'}</button></div></form>
    </div>}
    {open&&<div className="modal-backdrop"><form className="modal" onSubmit={saveComplaint}><div className="modal-head"><div><p className="eyebrow">CUSTOMER COMPLAINT</p><h2>Register Complaint</h2></div><button type="button" className="icon" onClick={()=>setOpen(false)}><X/></button></div><div className="form-grid"><label className="wide">Find customer<input value={memberSearch} placeholder="Name, member number, registration/form number, or contact" onChange={e=>setMemberSearch(e.target.value)}/></label><div className="wide"><button type="button" className="secondary" onClick={()=>void searchMemberChoices()}><Search size={15}/> Search customers</button></div><label className="wide">Customer / Member<select required value={form.memberId} onChange={e=>setForm({...form,memberId:e.target.value})}><option value="">Select customer</option>{members.map(member=><option key={member.id} value={member.id}>{member.name} — {member.mbrNo}{member.regNo?` · ${member.regNo}`:''}</option>)}</select></label><label className="wide">Subject<input required maxLength={255} value={form.subject} onChange={e=>setForm({...form,subject:e.target.value})}/></label><label className="wide">Complaint details<textarea value={form.description} onChange={e=>setForm({...form,description:e.target.value})}/></label></div><p className="muted">The complaint is recorded as an OPEN Customer Care case. Use the customer profile to view existing registration and plot context; the case schema does not store a direct sale/plot association.</p>{error&&<div className="error">{error}</div>}<div className="modal-actions"><button type="button" className="secondary" onClick={()=>setOpen(false)}>Cancel</button><button className="primary" disabled={saving||!form.memberId}>{saving?'Registering…':'Register Complaint'}</button></div></form></div>}
  </div>;
}
function CareOverview(){const [summary,setSummary]=useState<Record<string,unknown>|null>(null);const [error,setError]=useState('');useEffect(()=>{api<Record<string,unknown>>('/customer-care/summary').then(setSummary).catch(e=>setError(e.message))},[]);return <>{error&&<div className="error page-error">{error}</div>}<div className="stat-grid">{[['Open Cases','openCases'],['Open Tickets','openTickets'],['Pending Follow-ups','pendingFollowups'],['Total Enquiries','totalEnquiries']].map(([label,key])=><div className="stat" key={key}><Headphones size={18} className="stat-icon"/><span>{label}</span><strong>{summary?String(summary[key]??0):'…'}</strong><small>Current customer-care register</small></div>)}</div><div className="panel empty">Use the tabs above to work with the complete Customer Care register.</div></>}
type CareCustomerSearchResult = { id:number; mbrNo:string; regNo?:string; name:string; contact1?:string; status?:string };
type CareCustomerRegistration = {
  registrationId:number; registrationNo:string; formNo?:string; category?:string;
  registrationStatus?:string; bookingId?:number; bookingStatus?:string; bookingCategory?:string;
  plotId?:number; plotNo?:string; plotSizeMarla?:number; plotType?:string;
  plotCategory?:string; plotStatus?:string; blockName?:string;
};
type CareCustomerProfileData = {
  customer:Record<string,unknown>;
  registrations:CareCustomerRegistration[];
  documents:Record<string,unknown>[];
};
function CareCustomerLookup(){
  const [query,setQuery]=useState('');
  const [rows,setRows]=useState<CareCustomerSearchResult[]>([]);
  const [profile,setProfile]=useState<CareCustomerProfileData|null>(null);
  const [loading,setLoading]=useState(false);
  const [profileLoading,setProfileLoading]=useState(false);
  const [error,setError]=useState('');
  async function search(e:React.FormEvent){
    e.preventDefault();
    if(!query.trim()){setError('Enter a name, member number, registration/form number, or contact number.');return;}
    setLoading(true);setError('');setProfile(null);setRows([]);
    try{setRows(await api<CareCustomerSearchResult[]>(`/customer-care/customers?q=${encodeURIComponent(query.trim())}`));}
    catch(err){setError(err instanceof Error?err.message:'Unable to search customer records.');}
    finally{setLoading(false);}
  }
  async function openProfile(customer:CareCustomerSearchResult){
    setProfile(null);setProfileLoading(true);setError('');
    try{setProfile(await api<CareCustomerProfileData>(`/customer-care/customers/${customer.id}`));}
    catch(err){setError(err instanceof Error?err.message:'Unable to load the customer profile.');}
    finally{setProfileLoading(false);}
  }
  const customer=profile?.customer;
  return <div className="config-workspace">
    <div className="panel">
      <div className="panel-head"><div><p className="eyebrow">CUSTOMER DIRECTORY</p><h3>Search customer records</h3></div></div>
      <form className="search" onSubmit={search}><Search size={18}/><input aria-label="Search customer" placeholder="Name, member number, registration/form number, or contact" value={query} onChange={e=>setQuery(e.target.value)}/><button className="primary" disabled={loading}>{loading?'Searching…':'Search'}</button></form>
      <p className="muted">Customer Care profile shows identity, contact, registrations, bookings, plots and active document metadata. Financial ledgers are not shown here.</p>
      {error&&<div className="error page-error">{error}</div>}
      {rows.length>0&&<div className="table-wrap"><table><thead><tr><th>Member No.</th><th>Customer</th><th>Registration</th><th>Contact</th><th>Status</th><th></th></tr></thead><tbody>{rows.map(row=><tr key={row.id}><td className="code">{row.mbrNo||'—'}</td><td><b>{row.name}</b></td><td>{row.regNo||'—'}</td><td>{row.contact1||'—'}</td><td><span className="badge">{row.status||'—'}</span></td><td><button className="secondary compact" onClick={()=>void openProfile(row)}>View profile</button></td></tr>)}</tbody></table></div>}
      {!loading&&query.trim()&&rows.length===0&&!error&&<div className="empty">No customer records match this search.</div>}
    </div>
    {profileLoading&&<div className="panel"><div className="empty">Loading customer profile…</div></div>}
    {customer&&<div className="panel">
      <div className="panel-head"><div><p className="eyebrow">CUSTOMER PROFILE</p><h3>{String(customer.name||'Customer')} <span className="muted">· {String(customer.mbrNo||'')}</span></h3></div><button className="secondary" onClick={()=>setProfile(null)}>Close</button></div>
      <div className="stat-grid">
        {[['CNIC','cnicNo'],['Primary contact','contact1'],['Secondary contact','contact2'],['Landline','landline1'],['Email','email1'],['City','city'],['Status','status']].map(([label,key])=><div className="stat" key={key}><span>{label}</span><strong style={{fontSize:'15px'}}>{String(customer[key]||'—')}</strong></div>)}
        <div className="stat"><span>Address</span><strong style={{fontSize:'15px'}}>{[customer.address1,customer.address2,customer.country].filter(Boolean).map(String).join(', ')||'—'}</strong></div>
      </div>
      <div className="panel-head" style={{marginTop:'22px'}}><div><p className="eyebrow">EXISTING SALE RELATIONSHIPS</p><h3>Registrations, bookings and plots</h3></div></div>
      <div className="table-wrap"><table><thead><tr><th>Registration</th><th>Form / Category</th><th>Booking</th><th>Plot / Unit</th><th>Plot type</th><th>Registration / booking / plot status</th></tr></thead><tbody>{profile.registrations.map((item,index)=><tr key={`${item.registrationId}-${item.bookingId||'none'}-${index}`}><td>{item.registrationNo||`#${item.registrationId}`}<small>#{item.registrationId}</small></td><td>{item.formNo||'—'}<small>{item.bookingCategory||item.category||'—'}</small></td><td>{item.bookingId?`#${item.bookingId}`:'—'}</td><td>{item.plotNo||'—'}<small>{item.blockName||''}{item.plotSizeMarla?` · ${item.plotSizeMarla} Marla`:''}</small></td><td>{item.plotType||item.plotCategory||'—'}</td><td>{[item.registrationStatus,item.bookingStatus,item.plotStatus].filter(Boolean).join(' / ')||'—'}</td></tr>)}</tbody></table>{profile.registrations.length===0&&<div className="empty">No registration or booking records are linked to this customer.</div>}</div>
      <div className="panel-head" style={{marginTop:'22px'}}><div><p className="eyebrow">CUSTOMER DOCUMENTS</p><h3>Active document records</h3></div></div>
      <div className="table-wrap"><table><thead><tr><th>Document</th><th>Type</th><th>Related booking</th><th>Uploaded</th><th>Status</th></tr></thead><tbody>{profile.documents.map(doc=><tr key={String(doc.id)}><td>{String(doc.originalName||'—')}</td><td>{String(doc.documentType||'—')}</td><td>{doc.bookingId?`#${String(doc.bookingId)}`:'—'}</td><td>{String(doc.uploadedAt||'—').replace('T',' ').slice(0,19)}</td><td><span className="badge">{String(doc.status||'—')}</span></td></tr>)}</tbody></table>{profile.documents.length===0&&<div className="empty">No active document records are linked to this customer.</div>}</div>
    </div>}
  </div>;
}
function CareEnquiries(){const [rows,setRows]=useState<Record<string,unknown>[]>([]);const [members,setMembers]=useState<Customer[]>([]);const [open,setOpen]=useState(false);const [error,setError]=useState('');const [saving,setSaving]=useState(false);const blank={memberId:'',prospectName:'',contactNo:'',subject:'',enquiryType:'GENERAL',description:'',followUpDate:'',remarks:''};const [form,setForm]=useState(blank);const load=()=>Promise.all([api<Record<string,unknown>[]>('/customer-care/enquiries'),api<Customer[]>('/customer-care/customers')]).then(([r,m])=>{setRows(r);setMembers(m)}).catch(e=>setError(e.message));useEffect(() => { load(); }, []);async function save(e:React.FormEvent){e.preventDefault();setSaving(true);try{await api('/customer-care/enquiries',{method:'POST',body:JSON.stringify({...form,memberId:form.memberId?Number(form.memberId):null,followUpDate:form.followUpDate||null})});setOpen(false);setForm(blank);load()}catch(e){setError(e instanceof Error?e.message:'Unable to save enquiry.')}finally{setSaving(false)}}return <div className="panel"><div className="panel-head"><div><p className="eyebrow">ENQUIRY REGISTER</p><h3>Enquiries</h3></div><button className="primary" onClick={()=>setOpen(true)}><Plus size={15}/> New Enquiry</button></div>{error&&<div className="error page-error">{error}</div>}<div className="table-wrap"><table><thead><tr><th>Subject</th><th>Prospect / Member</th><th>Type</th><th>Follow-up</th><th>Status</th></tr></thead><tbody>{rows.map((r,i)=><tr key={i}><td><b>{String(r.subject||'—')}</b><small>{String(r.description||'')}</small></td><td>{String(r.memberName||r.prospectName||'—')}<small>{String(r.mbrNo||r.contactNo||'')}</small></td><td>{String(r.enquiryType||'GENERAL')}</td><td>{String(r.followUpDate||'—').slice(0,10)}</td><td><span className="badge">{String(r.status||'—')}</span></td></tr>)}</tbody></table></div>{open&&<div className="modal-backdrop"><form className="modal" onSubmit={save}><div className="modal-head"><div><p className="eyebrow">CUSTOMER ENQUIRY</p><h2>New Enquiry</h2></div><button type="button" className="icon" onClick={()=>setOpen(false)}><X/></button></div><div className="form-grid"><label>Member<select required value={form.memberId} onChange={e=>setForm({...form,memberId:e.target.value})}><option value="">Select member</option>{members.map(m=><option key={m.id} value={m.id}>{m.name} — {m.mbrNo}</option>)}</select></label><label>Prospect Name<input value={form.prospectName} onChange={e=>setForm({...form,prospectName:e.target.value})}/></label><label>Contact<input value={form.contactNo} onChange={e=>setForm({...form,contactNo:e.target.value})}/></label><label>Enquiry Type<select value={form.enquiryType} onChange={e=>setForm({...form,enquiryType:e.target.value})}><option>GENERAL</option><option>SALES</option><option>FINANCE</option><option>TRANSFER</option><option>POSSESSION</option><option>NDC</option></select></label><label className="wide">Subject<input required value={form.subject} onChange={e=>setForm({...form,subject:e.target.value})}/></label><label className="wide">Description<textarea value={form.description} onChange={e=>setForm({...form,description:e.target.value})}/></label><label>Follow-up Date<input type="date" value={form.followUpDate} onChange={e=>setForm({...form,followUpDate:e.target.value})}/></label><label>Remarks<input value={form.remarks} onChange={e=>setForm({...form,remarks:e.target.value})}/></label></div><div className="modal-actions"><button type="button" className="secondary" onClick={()=>setOpen(false)}>Cancel</button><button className="primary" disabled={saving}>{saving?'Saving…':'Create Enquiry'}</button></div></form></div>}</div>}
function CareTickets(){const [rows,setRows]=useState<Record<string,unknown>[]>([]);const [members,setMembers]=useState<Customer[]>([]);const [open,setOpen]=useState(false);const [error,setError]=useState('');const [saving,setSaving]=useState(false);const blank={memberId:'',subject:'',category:'GENERAL',priority:'MEDIUM',description:'',dueDate:''};const [form,setForm]=useState(blank);const load=()=>Promise.all([api<Record<string,unknown>[]>('/customer-care/tickets'),api<Customer[]>('/customer-care/customers')]).then(([r,m])=>{setRows(r);setMembers(m)}).catch(e=>setError(e.message));useEffect(() => { load(); }, []);async function save(e:React.FormEvent){e.preventDefault();setSaving(true);try{await api('/customer-care/tickets',{method:'POST',body:JSON.stringify({...form,memberId:Number(form.memberId),dueDate:form.dueDate||null})});setOpen(false);setForm(blank);load()}catch(e){setError(e instanceof Error?e.message:'Unable to create ticket.')}finally{setSaving(false)}}async function closeTicket(id:unknown){const resolution=window.prompt('Enter resolution / closing note:','Resolved');if(resolution===null)return;try{await api(`/customer-care/tickets/${id}/status`,{method:'PUT',body:JSON.stringify({status:'CLOSED',resolution})});load()}catch(e){setError(e instanceof Error?e.message:'Unable to close ticket.')}}return <div className="panel"><div className="panel-head"><div><p className="eyebrow">SERVICE TICKETS</p><h3>Customer Service Tickets</h3></div><button className="primary" onClick={()=>setOpen(true)}><Plus size={15}/> New Ticket</button></div>{error&&<div className="error page-error">{error}</div>}<div className="table-wrap"><table><thead><tr><th>Ticket</th><th>Member</th><th>Subject</th><th>Priority</th><th>Due</th><th>Status</th><th>Action</th></tr></thead><tbody>{rows.map((r,i)=><tr key={i}><td className="code">{String(r.ticketNo||'—')}</td><td>{String(r.memberName||'—')}<small>{String(r.mbrNo||'')}</small></td><td>{String(r.subject||'—')}</td><td>{String(r.priority||'—')}</td><td>{String(r.dueDate||'—').slice(0,10)}</td><td><span className="badge">{String(r.status||'—')}</span></td><td>{String(r.status)!=='CLOSED'&&<button className="secondary compact" onClick={()=>closeTicket(r.id)}>Close</button>}</td></tr>)}</tbody></table></div>{open&&<div className="modal-backdrop"><form className="modal" onSubmit={save}><div className="modal-head"><div><p className="eyebrow">SERVICE TICKET</p><h2>New Ticket</h2></div><button type="button" className="icon" onClick={()=>setOpen(false)}><X/></button></div><div className="form-grid"><label>Member<select required value={form.memberId} onChange={e=>setForm({...form,memberId:e.target.value})}><option value="">Select member</option>{members.map(m=><option key={m.id} value={m.id}>{m.name} — {m.mbrNo}</option>)}</select></label><label>Category<select value={form.category} onChange={e=>setForm({...form,category:e.target.value})}><option>GENERAL</option><option>SALES</option><option>FINANCE</option><option>POSSESSION</option><option>TRANSFER</option></select></label><label className="wide">Subject<input required value={form.subject} onChange={e=>setForm({...form,subject:e.target.value})}/></label><label>Priority<select value={form.priority} onChange={e=>setForm({...form,priority:e.target.value})}><option>LOW</option><option>MEDIUM</option><option>HIGH</option><option>URGENT</option></select></label><label>Due Date<input type="date" value={form.dueDate} onChange={e=>setForm({...form,dueDate:e.target.value})}/></label><label className="wide">Description<textarea value={form.description} onChange={e=>setForm({...form,description:e.target.value})}/></label></div><div className="modal-actions"><button type="button" className="secondary" onClick={()=>setOpen(false)}>Cancel</button><button className="primary" disabled={saving}>{saving?'Saving…':'Create Ticket'}</button></div></form></div>}</div>}
function CareFollowups(){const [rows,setRows]=useState<Record<string,unknown>[]>([]);const [members,setMembers]=useState<Customer[]>([]);const [open,setOpen]=useState(false);const [error,setError]=useState('');const [saving,setSaving]=useState(false);const blank={memberId:'',followUpDate:new Date().toISOString().slice(0,10),purpose:'GENERAL',notes:''};const [form,setForm]=useState(blank);const load=()=>Promise.all([api<Record<string,unknown>[]>('/customer-care/followups?status=PENDING'),api<Customer[]>('/customer-care/customers')]).then(([r,m])=>{setRows(r);setMembers(m)}).catch(e=>setError(e.message));useEffect(() => { load(); }, []);async function save(e:React.FormEvent){e.preventDefault();setSaving(true);try{await api('/customer-care/followups',{method:'POST',body:JSON.stringify({...form,memberId:Number(form.memberId)})});setOpen(false);setForm(blank);load()}catch(e){setError(e instanceof Error?e.message:'Unable to create follow-up.')}finally{setSaving(false)}}return <div className="panel"><div className="panel-head"><div><p className="eyebrow">FOLLOW-UP REGISTER</p><h3>Pending Follow-ups</h3></div><button className="primary" onClick={()=>setOpen(true)}><Plus size={15}/> New Follow-up</button></div>{error&&<div className="error page-error">{error}</div>}<div className="table-wrap"><table><thead><tr><th>Date</th><th>Member</th><th>Purpose</th><th>Ticket</th><th>Notes</th><th>Status</th></tr></thead><tbody>{rows.map((r,i)=><tr key={i}><td>{String(r.followUpDate||'').slice(0,10)}</td><td>{String(r.memberName||'—')}<small>{String(r.mbrNo||'')}</small></td><td>{String(r.purpose||'—')}</td><td>{String(r.ticketNo||'—')}</td><td>{String(r.notes||'—')}</td><td><span className="badge">{String(r.status||'—')}</span></td></tr>)}</tbody></table></div>{open&&<div className="modal-backdrop"><form className="modal" onSubmit={save}><div className="modal-head"><div><p className="eyebrow">FOLLOW-UP</p><h2>Schedule Follow-up</h2></div><button type="button" className="icon" onClick={()=>setOpen(false)}><X/></button></div><div className="form-grid"><label>Member<select required value={form.memberId} onChange={e=>setForm({...form,memberId:e.target.value})}><option value="">Select member</option>{members.map(m=><option key={m.id} value={m.id}>{m.name} — {m.mbrNo}</option>)}</select></label><label>Date<input required type="date" value={form.followUpDate} onChange={e=>setForm({...form,followUpDate:e.target.value})}/></label><label>Purpose<input required value={form.purpose} onChange={e=>setForm({...form,purpose:e.target.value})}/></label><label className="wide">Notes<input value={form.notes} onChange={e=>setForm({...form,notes:e.target.value})}/></label></div><div className="modal-actions"><button type="button" className="secondary" onClick={()=>setOpen(false)}>Cancel</button><button className="primary" disabled={saving}>{saving?'Saving…':'Schedule Follow-up'}</button></div></form></div>}</div>}
function CareFeedback(){const [rows,setRows]=useState<Record<string,unknown>[]>([]);const [members,setMembers]=useState<Customer[]>([]);const [open,setOpen]=useState(false);const [error,setError]=useState('');const [saving,setSaving]=useState(false);const blank={memberId:'',feedbackText:'',rating:'',remarks:''};const [form,setForm]=useState(blank);const load=()=>api<Record<string,unknown>[]>('/customer-care/feedback').then(setRows).catch(e=>setError(e.message));useEffect(()=>{load();api<Customer[]>('/customer-care/customers').then(setMembers).catch(()=>{})},[]);async function save(e:React.FormEvent){e.preventDefault();setSaving(true);try{await api('/customer-care/feedback',{method:'POST',body:JSON.stringify({...form,memberId:Number(form.memberId),rating:form.rating?Number(form.rating):null})});setOpen(false);setForm(blank);load()}catch(e){setError(e instanceof Error?e.message:'Unable to save feedback.')}finally{setSaving(false)}}return <div className="panel"><div className="panel-head"><div><p className="eyebrow">CUSTOMER FEEDBACK</p><h3>Feedback Register</h3></div><button className="primary" onClick={()=>setOpen(true)}><Plus size={15}/> Add Feedback</button></div>{error&&<div className="error page-error">{error}</div>}<div className="table-wrap"><table><thead><tr><th>Date</th><th>Member</th><th>Feedback</th><th>Rating</th><th>Remarks</th></tr></thead><tbody>{rows.map((r,i)=><tr key={i}><td>{String(r.createdAt||'').slice(0,10)}</td><td>{String(r.memberName||'—')}<small>{String(r.mbrNo||'')}</small></td><td>{String(r.feedbackText||'—')}</td><td>{String(r.rating||'—')}</td><td>{String(r.remarks||'—')}</td></tr>)}</tbody></table></div>{open&&<div className="modal-backdrop"><form className="modal" onSubmit={save}><div className="modal-head"><div><p className="eyebrow">FEEDBACK</p><h2>Add Customer Feedback</h2></div><button type="button" className="icon" onClick={()=>setOpen(false)}><X/></button></div><div className="form-grid"><label>Member<select required value={form.memberId} onChange={e=>setForm({...form,memberId:e.target.value})}><option value="">Select member</option>{members.map(m=><option key={m.id} value={m.id}>{m.name} — {m.mbrNo}</option>)}</select></label><label>Rating<select value={form.rating} onChange={e=>setForm({...form,rating:e.target.value})}><option value="">No rating</option><option value="1">1</option><option value="2">2</option><option value="3">3</option><option value="4">4</option><option value="5">5</option></select></label><label className="wide">Feedback<textarea required value={form.feedbackText} onChange={e=>setForm({...form,feedbackText:e.target.value})}/></label><label className="wide">Remarks<input value={form.remarks} onChange={e=>setForm({...form,remarks:e.target.value})}/></label></div><div className="modal-actions"><button type="button" className="secondary" onClick={()=>setOpen(false)}>Cancel</button><button className="primary" disabled={saving}>{saving?'Saving…':'Save Feedback'}</button></div></form></div>}</div>}
function CareAnnouncements(){const [rows,setRows]=useState<Record<string,unknown>[]>([]);const [open,setOpen]=useState(false);const [error,setError]=useState('');const [saving,setSaving]=useState(false);const blank={title:'',body:'',venue:'',audience:'ALL',eventDate:'',status:'PUBLISHED'};const [form,setForm]=useState(blank);const load=()=>api<Record<string,unknown>[]>('/customer-care/announcements').then(setRows).catch(e=>setError(e.message));useEffect(() => { load(); }, []);async function save(e:React.FormEvent){e.preventDefault();setSaving(true);try{await api('/customer-care/announcements',{method:'POST',body:JSON.stringify({...form,eventDate:form.eventDate||null})});setOpen(false);setForm(blank);load()}catch(e){setError(e instanceof Error?e.message:'Unable to publish announcement.')}finally{setSaving(false)}}return <div className="panel"><div className="panel-head"><div><p className="eyebrow">SOCIETY COMMUNICATION</p><h3>Announcements & Events</h3></div><button className="primary" onClick={()=>setOpen(true)}><Plus size={15}/> Publish Announcement</button></div>{error&&<div className="error page-error">{error}</div>}<div className="table-wrap"><table><thead><tr><th>Title</th><th>Audience</th><th>Venue</th><th>Event Date</th><th>Status</th></tr></thead><tbody>{rows.map((r,i)=><tr key={i}><td><b>{String(r.title||'—')}</b><small>{String(r.body||'')}</small></td><td>{String(r.audience||'ALL')}</td><td>{String(r.venue||'—')}</td><td>{String(r.eventDate||'—').slice(0,10)}</td><td><span className="badge">{String(r.status||'—')}</span></td></tr>)}</tbody></table></div>{open&&<div className="modal-backdrop"><form className="modal" onSubmit={save}><div className="modal-head"><div><p className="eyebrow">ANNOUNCEMENT</p><h2>Publish Announcement</h2></div><button type="button" className="icon" onClick={()=>setOpen(false)}><X/></button></div><div className="form-grid"><label className="wide">Title<input required value={form.title} onChange={e=>setForm({...form,title:e.target.value})}/></label><label className="wide">Body<textarea required value={form.body} onChange={e=>setForm({...form,body:e.target.value})}/></label><label>Venue<input value={form.venue} onChange={e=>setForm({...form,venue:e.target.value})}/></label><label>Audience<select value={form.audience} onChange={e=>setForm({...form,audience:e.target.value})}><option>ALL</option><option>MEMBERS</option><option>STAFF</option></select></label><label>Event Date<input type="date" value={form.eventDate} onChange={e=>setForm({...form,eventDate:e.target.value})}/></label><label>Status<select value={form.status} onChange={e=>setForm({...form,status:e.target.value})}><option>PUBLISHED</option><option>DRAFT</option></select></label></div><div className="modal-actions"><button type="button" className="secondary" onClick={()=>setOpen(false)}>Cancel</button><button className="primary" disabled={saving}>{saving?'Publishing…':'Publish'}</button></div></form></div>}</div>}
function Care() { return <CareWorkspace />; }

function Documents() { return <DataPanel title="Documents" endpoint="/documents" columns={['documentType','originalName','mbrNo','name','status','uploadedAt']} />; }
type ReportResponse = { rows: Record<string, unknown>[]; summary: Record<string, unknown>; truncated: boolean; summaryTruncated: boolean; maxRows: number };
type ReportKind = 'plot-inventory' | 'plot-merges' | 'plot-cancellations' | 'receivables-aging' | 'receipt-register';

const reportDefinitions: Record<ReportKind, { label: string; endpoint: string; module: string }> = {
  'plot-inventory': { label: 'Plot Inventory', endpoint: '/reports/plots/inventory', module: 'Plots & Sales' },
  'plot-merges': { label: 'Plot Merging Register & History', endpoint: '/reports/sales/merges', module: 'Plots & Sales' },
  'plot-cancellations': { label: 'Plot Cancellation & Refund Register', endpoint: '/reports/sales/cancellations', module: 'Plots & Sales' },
  'receivables-aging': { label: 'Overdue Receivables Aging', endpoint: '/reports/finance/receivables-aging', module: 'Finance' },
  'receipt-register': { label: 'Finance Receipt Register', endpoint: '/reports/finance/receipts', module: 'Finance' }
};

function reportCell(value: unknown): string {
  if (value === null || value === undefined) return '—';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

function reportCsvCell(value: unknown): string {
  const text = reportCell(value);
  const protectedText = /^[\s]*[=+\-@]/.test(text) ? `'${text}` : text;
  return `"${protectedText.replace(/"/g, '""')}"`;
}

function reportHtmlCell(value: unknown): string {
  return reportCell(value).replace(/[&<>"']/g, character => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[character] ?? character);
}

function ReportsWorkspace({ user }: { user: User }) {
  const availableKinds = (Object.keys(reportDefinitions) as ReportKind[])
    .filter(reportKind => user.modules?.includes(reportDefinitions[reportKind].module));
  const [kind, setKind] = useState<ReportKind>(() => availableKinds[0] ?? 'plot-inventory');
  const [filters, setFilters] = useState({ fromDate: '', toDate: '', asOfDate: '', projectId: '', location: '', customer: '', status: '', refundStatus: '', paymentMethod: '', bucket: '', query: '', availability: '' });
  const [report, setReport] = useState<ReportResponse | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);
  const [viewSearch, setViewSearch] = useState('');
  const definition = reportDefinitions[kind];

  async function loadReport() {
    if (!user.modules?.includes(definition.module)) {
      setReport(null);
      setError('You are not authorized to view this report.');
      return;
    }
    setLoading(true);
    setError('');
    const params = new URLSearchParams();
    const add = (key: string, value: string) => { if (value.trim()) params.set(key, value.trim()); };
    if (kind === 'plot-inventory') {
      add('q', filters.query); add('availability', filters.availability); add('status', filters.status); add('projectId', filters.projectId);
    } else {
      add('fromDate', filters.fromDate); add('toDate', filters.toDate); add('projectId', filters.projectId);
      add('location', filters.location); add('customer', filters.customer);
      if (kind === 'plot-merges') add('status', filters.status);
      if (kind === 'plot-cancellations') { add('status', filters.status); add('refundStatus', filters.refundStatus); }
      if (kind === 'receivables-aging') { add('asOfDate', filters.asOfDate); add('bucket', filters.bucket); }
      if (kind === 'receipt-register') { add('status', filters.status); add('paymentMethod', filters.paymentMethod); }
    }
    try {
      const suffix = params.toString();
      const response = await api<ReportResponse>(`${definition.endpoint}${suffix ? `?${suffix}` : ''}`);
      setReport(response);
    } catch (loadError) {
      setReport(null);
      setError(loadError instanceof Error ? loadError.message : 'Unable to load this report.');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (!availableKinds.length) return;
    if (!availableKinds.includes(kind)) {
      setKind(availableKinds[0]);
      return;
    }
    void loadReport();
  }, [kind, refreshKey]);

  const rows = report?.rows ?? [];
  const displayedRows = viewSearch.trim()
    ? rows.filter(row => Object.values(row).some(value => reportCell(value).toLowerCase().includes(viewSearch.trim().toLowerCase())))
    : rows;
  const columns = [...new Set(displayedRows.flatMap(row => Object.keys(row)))];
  const setFilter = (key: keyof typeof filters, value: string) => setFilters(current => ({ ...current, [key]: value }));

  function download(name: string, data: string, type: string, extension: string) {
    const blob = new Blob([data], { type });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${name}.${extension}`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function exportCsv() {
    if (!columns.length) return;
    const csv = [columns.map(reportCsvCell).join(','), ...displayedRows.map(row => columns.map(column => reportCsvCell(row[column])).join(','))].join('\r\n');
    download(kind, `\uFEFF${csv}`, 'text/csv;charset=utf-8', 'csv');
  }

  function exportExcel() {
    if (!columns.length) return;
    const table = `<table><thead><tr>${columns.map(column => `<th>${reportHtmlCell(column)}</th>`).join('')}</tr></thead><tbody>${displayedRows.map(row => `<tr>${columns.map(column => `<td>${reportHtmlCell(row[column])}</td>`).join('')}</tr>`).join('')}</tbody></table>`;
    download(kind, `\uFEFF<html><head><meta charset="utf-8"></head><body>${table}</body></html>`, 'application/vnd.ms-excel;charset=utf-8', 'xls');
  }

  if (!availableKinds.length) return <div className="empty-page"><h1>No report access is assigned</h1><p className="muted">Reports require a report permission and access to the relevant source module.</p></div>;

  return <div className="reports-printable">
    <div className="toolbar report-controls">
      <div><p className="eyebrow">SCOPED REPORTS</p><h1>Reports</h1><p className="muted">Report data is limited to your active project and location authorization.</p></div>
      <label>Report<select value={kind} onChange={event => setKind(event.target.value as ReportKind)}>{availableKinds.map(value => <option key={value} value={value}>{reportDefinitions[value].label}</option>)}</select></label>
    </div>
    <section className="panel report-controls">
      <div className="form-grid">
        {kind === 'plot-inventory' ? <>
          <label>Search<input value={filters.query} onChange={event => setFilter('query', event.target.value)} /></label>
          <label>Project ID<input type="number" min="1" value={filters.projectId} onChange={event => setFilter('projectId', event.target.value)} /></label>
          <label>Plot status<input value={filters.status} onChange={event => setFilter('status', event.target.value)} placeholder="Optional" /></label>
          <label>Availability<input value={filters.availability} onChange={event => setFilter('availability', event.target.value)} placeholder="Optional" /></label>
        </> : <>
          <label>From date<input type="date" value={filters.fromDate} onChange={event => setFilter('fromDate', event.target.value)} /></label>
          <label>To date<input type="date" value={filters.toDate} onChange={event => setFilter('toDate', event.target.value)} /></label>
          <label>Project ID<input type="number" min="1" value={filters.projectId} onChange={event => setFilter('projectId', event.target.value)} /></label>
          <label>Location<input value={filters.location} onChange={event => setFilter('location', event.target.value)} /></label>
          <label>Customer / reference<input value={filters.customer} onChange={event => setFilter('customer', event.target.value)} /></label>
          {kind === 'plot-merges' && <label>Approval status<input value={filters.status} onChange={event => setFilter('status', event.target.value)} placeholder="Optional" /></label>}
          {kind === 'plot-cancellations' && <>
            <label>Cancellation status<input value={filters.status} onChange={event => setFilter('status', event.target.value)} placeholder="Optional" /></label>
            <label>Refund status<input value={filters.refundStatus} onChange={event => setFilter('refundStatus', event.target.value)} placeholder="Optional" /></label>
          </>}
          {kind === 'receivables-aging' && <>
            <label>As-of date<input type="date" value={filters.asOfDate} onChange={event => setFilter('asOfDate', event.target.value)} /></label>
            <label>Aging bucket<select value={filters.bucket} onChange={event => setFilter('bucket', event.target.value)}><option value="">All buckets</option><option>1-30</option><option>31-60</option><option>61-90</option><option>90+</option></select></label>
          </>}
          {kind === 'receipt-register' && <>
            <label>Receipt status<input value={filters.status} onChange={event => setFilter('status', event.target.value)} placeholder="Optional" /></label>
            <label>Payment method<input value={filters.paymentMethod} onChange={event => setFilter('paymentMethod', event.target.value)} placeholder="Optional" /></label>
          </>}
        </>}
      </div>
      <div className="modal-actions"><button className="primary" onClick={() => setRefreshKey(key => key + 1)} disabled={loading}>{loading ? 'Loading…' : 'Run report'}</button>
        {displayedRows.length > 0 && <><button className="secondary" onClick={exportCsv}>Export CSV</button><button className="secondary" onClick={exportExcel}>Export Excel</button><button className="secondary" onClick={() => window.print()}>Print / PDF</button></>}
      </div>
    </section>
    {error && <div className="error page-error">{error}</div>}
    {report && <section className="panel">
      <div className="panel-head"><div><p className="eyebrow">REPORT RESULTS</p><h3>{definition.label}</h3></div><span className="muted">{displayedRows.length} row(s)</span></div>
      <label className="report-controls">Search displayed rows<input value={viewSearch} onChange={event => setViewSearch(event.target.value)} /></label>
      <div className="report-summary">{Object.entries(report.summary).map(([key, value]) => <div className="stat" key={key}><span>{key.replace(/([A-Z])/g, ' $1')}</span><strong>{reportCell(value)}</strong></div>)}</div>
      {report.truncated && <div className="login-notice">Showing the first {report.maxRows} rows. Summary totals reflect only the displayed rows; narrow the filters for a complete result.</div>}
      {displayedRows.length === 0 ? <div className="empty">No records match the selected filters.</div> : <div className="table-wrap"><table><thead><tr>{columns.map(column => <th key={column}>{column.replace(/([A-Z])/g, ' $1')}</th>)}</tr></thead><tbody>{displayedRows.map((row, index) => <tr key={index}>{columns.map(column => <td key={column}>{reportCell(row[column])}</td>)}</tr>)}</tbody></table></div>}
    </section>}
  </div>;
}
type MobileAdminSection = 'projects' | 'news' | 'events' | 'visitor-info' | 'documents';
type MobileAdminRecord = {
  id: number; title: string; section: string; category?: string; summary?: string; description?: string;
  article?: string; location?: string; status?: string; publicationDate?: string; eventTime?: string;
  venue?: string; registrationInfo?: string; image?: string; documentCategory?: string;
  fileAssetId?: string; fileUrl?: string; fileName?: string; publishState: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  sortOrder?: number;
};
type MobileAdminForm = Omit<MobileAdminRecord, 'id' | 'section' | 'publishState' | 'fileUrl' | 'fileName'>;
type MobileAdminAsset = { id: string; url: string; fileName: string; contentType: string; size: number };

const mobileAdminSections: { key: MobileAdminSection; label: string; icon: LucideIcon }[] = [
  { key: 'projects', label: 'Projects', icon: Building2 },
  { key: 'news', label: 'News & Announcements', icon: Newspaper },
  { key: 'events', label: 'Events', icon: CalendarDays },
  { key: 'visitor-info', label: 'Visitor Information', icon: Smartphone },
  { key: 'documents', label: 'Documents', icon: FileText }
];

function MobileAppContentWorkspace() {
  const [section, setSection] = useState<MobileAdminSection>('projects');
  const [rows, setRows] = useState<MobileAdminRecord[]>([]);
  const [search, setSearch] = useState('');
  const [filterState, setFilterState] = useState('ALL');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);
  const saveLock = useRef(false);
  const uploadLock = useRef(false);
  const [rowAction, setRowAction] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [editing, setEditing] = useState<MobileAdminRecord | null>(null);
  const [preview, setPreview] = useState<MobileAdminRecord | null>(null);
  const [open, setOpen] = useState(false);
  const today = new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10);
  const blankForm: MobileAdminForm = {
    title: '', category: '', summary: '', description: '', article: '', location: '',
    status: section === 'events' ? 'UPCOMING' : '', publicationDate: today, eventTime: '',
    venue: '', registrationInfo: '', image: '', documentCategory: '', fileAssetId: '', sortOrder: 0
  };
  const [form, setForm] = useState<MobileAdminForm>(blankForm);
  const selected = mobileAdminSections.find(item => item.key === section)!;

  async function loadRows() {
    setLoading(true);
    setError('');
    try {
      const params = new URLSearchParams({ q: search.trim(), state: filterState });
      setRows(await api<MobileAdminRecord[]>(`/admin/mobile-content/${section}?${params.toString()}`));
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : 'Unable to load mobile app content.');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void loadRows(); }, [section, search, filterState]);

  function startCreate() {
    setEditing(null);
    setForm({ ...blankForm, status: section === 'events' ? 'UPCOMING' : '' });
    setError('');
    setOpen(true);
  }

  function startEdit(row: MobileAdminRecord) {
    setEditing(row);
    setForm({
      title: row.title ?? '', category: row.category ?? '', summary: row.summary ?? '',
      description: row.description ?? '', article: row.article ?? '', location: row.location ?? '',
      status: row.status ?? '', publicationDate: row.publicationDate?.slice(0, 10) ?? today,
      eventTime: row.eventTime ?? '', venue: row.venue ?? '', registrationInfo: row.registrationInfo ?? '',
      image: row.image ?? '', documentCategory: row.documentCategory ?? '',
      fileAssetId: row.fileAssetId ?? '', sortOrder: row.sortOrder ?? 0
    });
    setError('');
    setOpen(true);
  }

  async function saveContent(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (saveLock.current || uploading) return;
    saveLock.current = true;
    const submitter = (event.nativeEvent as SubmitEvent).submitter;
    const publishState = submitter instanceof HTMLButtonElement ? submitter.value : 'DRAFT';
    setSaving(true);
    setError('');
    setMessage('');
    try {
      const payload = JSON.stringify({ ...form, publishState, sortOrder: Number(form.sortOrder || 0) });
      await api(`/admin/mobile-content/${section}${editing ? `/${editing.id}` : ''}`, {
        method: editing ? 'PUT' : 'POST',
        body: payload
      });
      setOpen(false);
      setEditing(null);
      await loadRows();
      setMessage(publishState === 'PUBLISHED' ? 'Content published successfully.' : 'Draft saved successfully.');
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : 'Unable to save mobile app content.');
    } finally {
      saveLock.current = false;
      setSaving(false);
    }
  }

  async function uploadFile(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.currentTarget.files?.[0];
    event.currentTarget.value = '';
    if (!file || uploadLock.current) return;
    uploadLock.current = true;
    setUploading(true);
    setError('');
    try {
      const body = new FormData();
      body.append('file', file);
      const purpose = section === 'documents' ? 'DOCUMENT' : 'IMAGE';
      const token = sessionStorage.getItem('webv9_token');
      const response = await fetch(`${API}/admin/mobile-content/assets?purpose=${purpose}`, {
        method: 'POST',
        credentials: 'include',
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        body
      });
      if (response.status === 401) {
        sessionStorage.clear();
        window.location.reload();
        throw new Error('Your session has expired. Please sign in again.');
      }
      if (!response.ok) {
        const failure = await response.json().catch(() => null) as { message?: string } | null;
        throw new Error(failure?.message || 'The file could not be uploaded.');
      }
      const asset = await response.json() as MobileAdminAsset;
      if (purpose === 'IMAGE' && !asset.contentType.startsWith('image/')) {
        throw new Error('Choose a JPG, PNG, or WebP image for this content.');
      }
      setForm(current => purpose === 'IMAGE'
        ? { ...current, image: asset.url }
        : { ...current, fileAssetId: asset.id });
      setMessage(`${asset.fileName} uploaded successfully.`);
    } catch (uploadError) {
      setError(uploadError instanceof Error ? uploadError.message : 'Unable to upload the selected file.');
    } finally {
      uploadLock.current = false;
      setUploading(false);
    }
  }

  async function changeState(row: MobileAdminRecord, action: 'publish' | 'unpublish' | 'archive') {
    if (rowAction !== null) return;
    if (action === 'archive' && !window.confirm(`Archive “${row.title}”? It will no longer appear in the mobile app.`)) return;
    setRowAction(row.id);
    setError('');
    setMessage('');
    try {
      await api(`/admin/mobile-content/${section}/${row.id}/${action}`, { method: 'POST' });
      await loadRows();
      setMessage(action === 'publish' ? 'Content published successfully.'
        : action === 'archive' ? 'Content archived successfully.' : 'Content unpublished and saved as a draft.');
    } catch (actionError) {
      setError(actionError instanceof Error ? actionError.message : 'Unable to update publication status.');
    } finally {
      setRowAction(null);
    }
  }

  const isArticle = section === 'news';
  const isEvent = section === 'events';
  const isDocument = section === 'documents';
  const needsDate = section === 'projects' || isArticle || isEvent;
  const recordDescription = (row: MobileAdminRecord) => row.summary || row.description || row.article || '—';

  return <div className="mobile-content-workspace">
    <div className="mobile-content-tabs" role="tablist" aria-label="Mobile app content categories">
      {mobileAdminSections.map(item => {
        const Icon = item.icon;
        return <button type="button" role="tab" aria-selected={section === item.key}
          className={section === item.key ? 'mobile-content-tab active' : 'mobile-content-tab'}
          key={item.key} onClick={() => { setSection(item.key); setMessage(''); }}>
          <Icon size={16} /><span>{item.label}</span>
        </button>;
      })}
    </div>

    <section className="panel mobile-content-register">
      <div className="panel-head mobile-content-heading">
        <div><p className="eyebrow">DISTRICT ONE MOBILE APP</p><h3>{selected.label} content</h3>
          <p className="muted">Create, preview, publish, and maintain content shown to mobile customers.</p>
        </div>
        <button type="button" className="primary" onClick={startCreate}><Plus size={15} /> New {isDocument ? 'document' : selected.label.replace(' & Announcements', '')}</button>
      </div>
      <div className="mobile-content-filters">
        <label className="mobile-content-search"><Search size={16} /><input aria-label="Search mobile content" value={search}
          onChange={event => setSearch(event.target.value)} placeholder="Search title, summary, description, or location" /></label>
        <label>Publication state<select value={filterState} onChange={event => setFilterState(event.target.value)}>
          <option value="ALL">All states</option><option value="DRAFT">Drafts</option>
          <option value="PUBLISHED">Published</option><option value="ARCHIVED">Archived</option>
        </select></label>
        <button type="button" className="secondary compact" onClick={() => void loadRows()} disabled={loading}><RefreshCw size={14} /> Refresh</button>
      </div>
      {error && <div className="error page-error" role="alert">{error}</div>}
      {message && <div className="success mobile-content-message" role="status">{message}</div>}
      {loading ? <div className="empty">Loading {selected.label.toLowerCase()}…</div>
        : rows.length === 0 ? <div className="empty">No {selected.label.toLowerCase()} match the selected search and publication filters.</div>
          : <div className="table-wrap mobile-content-table"><table><thead><tr>
            <th>Content</th><th>Details</th><th>Publication</th><th>Actions</th>
          </tr></thead><tbody>{rows.map(row => <tr key={row.id}>
            <td><b>{row.title}</b><small>{row.category || row.documentCategory || row.fileName || row.location || selected.label}</small></td>
            <td>{recordDescription(row)}{row.venue && <small>{row.venue} · {row.eventTime || ''}</small>}</td>
            <td><span className={`mobile-content-state ${row.publishState.toLowerCase()}`}>{row.publishState}</span>
              <small>{row.status ? `${row.status} · ` : ''}{row.publicationDate?.slice(0, 10) || 'No date'}</small></td>
            <td><div className="mobile-content-actions">
              <button type="button" className="secondary compact" aria-label={`Preview ${row.title}`} onClick={() => setPreview(row)}><Eye size={13} /> Preview</button>
              <button type="button" className="secondary compact" aria-label={`Edit ${row.title}`} onClick={() => startEdit(row)}><Pencil size={13} /> Edit</button>
              {row.publishState === 'PUBLISHED'
                ? <button type="button" className="secondary compact" disabled={rowAction !== null} onClick={() => void changeState(row, 'unpublish')}>{rowAction === row.id ? 'Saving…' : 'Unpublish'}</button>
                : row.publishState === 'ARCHIVED'
                  ? <button type="button" className="secondary compact" disabled={rowAction !== null} onClick={() => void changeState(row, 'unpublish')}>Restore draft</button>
                  : <button type="button" className="secondary compact" disabled={rowAction !== null} onClick={() => void changeState(row, 'publish')}>{rowAction === row.id ? 'Saving…' : 'Publish'}</button>}
              {row.publishState !== 'ARCHIVED' && <button type="button" className="secondary compact" disabled={rowAction !== null} aria-label={`Archive ${row.title}`} onClick={() => void changeState(row, 'archive')}><Archive size={13} /> Archive</button>}
              {row.fileUrl && <a className="secondary compact mobile-content-download" href={row.fileUrl} target="_blank" rel="noreferrer"><Download size={13} /> File</a>}
            </div></td>
          </tr>)}</tbody></table></div>}
    </section>

    {open && <div className="modal-backdrop"><form className="modal mobile-content-editor" onSubmit={saveContent}>
      <div className="modal-head"><div><p className="eyebrow">MOBILE APP / {selected.label.toUpperCase()}</p>
        <h2>{editing ? 'Edit content' : 'Create content'}</h2><p className="muted">New items are saved as drafts unless you select Publish.</p></div>
        <button type="button" className="icon" aria-label="Close editor" onClick={() => setOpen(false)}><X /></button>
      </div>
      <div className="form-grid">
        <label className="wide">Title<input required maxLength={255} value={form.title} onChange={event => setForm({ ...form, title: event.target.value })} /></label>
        {isArticle && <>
          <label>Category<input required maxLength={100} value={form.category || ''} onChange={event => setForm({ ...form, category: event.target.value })} /></label>
          <label>Publication date<input required type="date" value={form.publicationDate || ''} onChange={event => setForm({ ...form, publicationDate: event.target.value })} /></label>
          <label className="wide">Summary<input required maxLength={2000} value={form.summary || ''} onChange={event => setForm({ ...form, summary: event.target.value })} /></label>
          <label className="wide">Full article<textarea required maxLength={100000} rows={7} value={form.article || ''} onChange={event => setForm({ ...form, article: event.target.value })} /></label>
        </>}
        {!isArticle && <label className="wide">Description / visitor instructions<textarea required maxLength={100000} rows={5}
          value={form.description || ''} onChange={event => setForm({ ...form, description: event.target.value })} /></label>}
        {section === 'projects' && <>
          <label>Location<input value={form.location || ''} onChange={event => setForm({ ...form, location: event.target.value })} /></label>
          <label>Project status<input required maxLength={80} value={form.status || ''} onChange={event => setForm({ ...form, status: event.target.value })} placeholder="e.g. Under construction" /></label>
        </>}
        {isEvent && <>
          <label>Event date<input required type="date" value={form.publicationDate || ''} onChange={event => setForm({ ...form, publicationDate: event.target.value })} /></label>
          <label>Event time<input required type="time" value={form.eventTime || ''} onChange={event => setForm({ ...form, eventTime: event.target.value })} /></label>
          <label>Venue<input required maxLength={255} value={form.venue || ''} onChange={event => setForm({ ...form, venue: event.target.value })} /></label>
          <label>Event status<select value={form.status || 'UPCOMING'} onChange={event => setForm({ ...form, status: event.target.value })}>
            <option value="UPCOMING">Upcoming</option><option value="COMPLETED">Completed</option><option value="CANCELLED">Cancelled</option>
          </select></label>
          <label className="wide">Registration / contact information<input maxLength={1000} value={form.registrationInfo || ''} onChange={event => setForm({ ...form, registrationInfo: event.target.value })} /></label>
        </>}
        {isDocument && <>
          <label>Document category<input required maxLength={100} value={form.documentCategory || ''} onChange={event => setForm({ ...form, documentCategory: event.target.value })} placeholder="Brochure, map, visitor guide…" /></label>
          <label>Upload date<input type="date" value={form.publicationDate || ''} onChange={event => setForm({ ...form, publicationDate: event.target.value })} /></label>
        </>}
        {needsDate && !isArticle && !isEvent && <label>Publication date<input required type="date" value={form.publicationDate || ''} onChange={event => setForm({ ...form, publicationDate: event.target.value })} /></label>}
        {section !== 'visitor-info' && <label className="wide">Upload {isDocument ? 'document or visitor information file' : 'image'}
          <input type="file" accept={isDocument ? '.pdf,.jpg,.jpeg,.png,.webp,application/pdf,image/jpeg,image/png,image/webp' : 'image/jpeg,image/png,image/webp'}
            onChange={event => void uploadFile(event)} disabled={uploading} required={isDocument && !form.fileAssetId} />
          <small>{uploading ? 'Uploading and validating file…' : isDocument
            ? `PDF, JPG, PNG, or WebP · up to 10 MB${form.fileAssetId ? ` · ${editing?.fileName || 'File uploaded'}` : ''}`
            : form.image ? 'Image uploaded. Upload another image to replace it.' : 'JPG, PNG, or WebP · up to 10 MB'}</small>
        </label>}
        <label>Display order<input type="number" min="0" max="2147483647" value={form.sortOrder || 0} onChange={event => setForm({ ...form, sortOrder: Number(event.target.value) })} /></label>
      </div>
      {error && <div className="error" role="alert">{error}</div>}
      {message && open && <div className="success" role="status">{message}</div>}
      <div className="modal-actions mobile-content-form-actions">
        <button type="button" className="secondary" onClick={() => setOpen(false)}>Cancel</button>
        <button type="submit" className="secondary" value="DRAFT" disabled={saving || uploading}>{saving ? 'Saving…' : 'Save draft'}</button>
        <button type="submit" className="primary" value="PUBLISHED" disabled={saving || uploading}>{saving ? 'Saving…' : editing?.publishState === 'PUBLISHED' ? 'Save & publish' : 'Publish'}</button>
      </div>
    </form></div>}

    {preview && <div className="modal-backdrop" onMouseDown={event => { if (event.target === event.currentTarget) setPreview(null); }}>
      <section className="modal mobile-content-preview">
        <div className="modal-head"><div><p className="eyebrow">MOBILE APP PREVIEW / {selected.label.toUpperCase()}</p><h2>{preview.title}</h2></div>
          <button type="button" className="icon" aria-label="Close preview" onClick={() => setPreview(null)}><X /></button>
        </div>
        {preview.image && <img className="mobile-content-preview-image" src={preview.image} alt="" />}
        <div className="mobile-content-preview-meta">{preview.category || preview.documentCategory || ''}{preview.publicationDate ? ` · ${preview.publicationDate.slice(0, 10)}` : ''}{preview.status ? ` · ${preview.status}` : ''}</div>
        {preview.summary && <p className="mobile-content-preview-summary">{preview.summary}</p>}
        <div className="mobile-content-preview-body">{preview.article || preview.description || 'No description provided.'}</div>
        {preview.venue && <p className="muted">{preview.venue}{preview.eventTime ? ` · ${preview.eventTime}` : ''}</p>}
        {preview.fileUrl && <a className="secondary compact" href={preview.fileUrl} target="_blank" rel="noreferrer"><Download size={14} /> {preview.fileName || 'Open attached file'}</a>}
        <div className="modal-actions"><button type="button" className="secondary" onClick={() => setPreview(null)}>Close preview</button></div>
      </section>
    </div>}
  </div>;
}

const adminOptions: { title: string; description: string; icon: LucideIcon }[] = [
  { title: 'Mobile App Content', description: 'Manage mobile app projects, news, events, and visitor information.', icon: Smartphone },
  { title: 'Create Users', description: 'Manage access, roles and permissions.', icon: Users },
  { title: 'Job Title Permissions', description: 'Configure permissions for existing and additional designations.', icon: ShieldCheck },
  { title: 'Customer Data Import', description: 'Preview and validate source customer files before approval.', icon: FileText },
  { title: 'Charges Setup', description: 'Maintain charge heads and calculation rules.', icon: ReceiptText },
  { title: 'Bank Setup', description: 'Manage receiving bank accounts.', icon: Landmark },
  { title: 'Cash Site Setup', description: 'Maintain approved collection sites.', icon: Banknote },
  { title: 'Instalment Plan Setup', description: 'Configure payment plans and schedules.', icon: CalendarClock },
  { title: 'Surcharge Setup', description: 'Define grace periods and surcharge rules.', icon: Percent },
  { title: 'System Settings', description: 'Control safe application preferences.', icon: SlidersHorizontal },
  { title: 'Audit Log', description: 'Review protected system activity.', icon: ClipboardList }
];

function Admin({ user }: { user: User }) {
  const [active, setActive] = useState<string | null>(null);
  const [create, setCreate] = useState(false);
  const [editingUserId, setEditingUserId] = useState<number | null>(null);
  const [userListVersion, setUserListVersion] = useState(0);
  function open(option: string) {
    setActive(option);
    if (option === 'Create Users') { setCreate(false); setEditingUserId(null); }
  }
  const refreshUsers = () => setUserListVersion(version => version + 1);
  const allowedOptions = adminOptions.filter(item => user.superAdmin
    || item.title === 'Create Users' && user.modules?.includes('Users & Roles'));
  return <>
    {active === 'Mobile App Content' ? <div className="mobile-content-admin">
      <div className="toolbar"><div><p className="eyebrow">ADMINISTRATION / CONTENT PUBLISHING</p>
        <h1>Mobile App Content</h1><p className="muted">Manage mobile app projects, news, events, and visitor information.</p></div>
        <button type="button" className="secondary" onClick={() => setActive(null)}><ArrowLeft size={15} /> Admin &amp; Setting</button>
      </div>
      <MobileAppContentWorkspace />
    </div> : <>
      <div className="toolbar admin-toolbar"><div><p className="eyebrow">ADMINISTRATION</p><h1>Admin & Setting</h1><p className="muted">Configure controlled system operations and access.</p></div></div>
      <div className="admin-grid">{allowedOptions.map(({ title, description, icon: Icon }) =>
        <button className="admin-card" key={title} onClick={() => open(title)}>
          <span className="admin-card-icon"><Icon size={19} /></span>
          <span className="admin-card-title">{title}</span><small>{description}</small>
          <b className="admin-card-action">Open <span>→</span></b>
        </button>)}</div>
      {active && <div className="modal-backdrop"><section className="modal admin-modal">
        <div className="modal-head"><div><p className="eyebrow">ADMINISTRATION / {active.toUpperCase()}</p>
          <h2>{active}</h2><p className="muted">{adminOptions.find(item => item.title === active)?.description}</p></div>
          <button type="button" className="icon" aria-label="Close configuration" onClick={() => setActive(null)}><X /></button>
        </div>
        <div className="admin-modal-content">
          {active === 'Create Users' && <>
            <div className="modal-toolbar"><span className="muted">Current users and access status</span>
              {canPerformAction('Users & Roles', 'ADD') && <button className="primary" onClick={() => {
                setEditingUserId(null); setCreate(true);
              }}><Plus size={15} /> Create user</button>}
            </div>
            <AdminUsersList key={userListVersion} onEdit={id => { setEditingUserId(id); setCreate(true); }} />
          </>}
          {active === 'Job Title Permissions' && user.superAdmin && <JobTitlePermissions />}
          {active === 'Customer Data Import' && user.superAdmin && <CustomerDataImport />}
          {active === 'Audit Log' && user.superAdmin && <DataPanel title="Audit Log" endpoint="/admin/config/audit/log" columns={['username','action','module','details','occurredAt']} />}
          {user.superAdmin && active !== 'Create Users' && active !== 'Job Title Permissions'
            && active !== 'Customer Data Import' && active !== 'Audit Log' && <ConfigWorkspace module={active} />}
        </div>
      </section></div>}
    </>}
    {create && <AdminUserForm userId={editingUserId} actorIsSuperAdmin={Boolean(user.superAdmin)}
      onClose={() => setCreate(false)} onSaved={refreshUsers} />}
  </>;
}

function JobTitlePermissions() {
  const [positions, setPositions] = useState<AdminPositionOption[]>([]);
  const [permissions, setPermissions] = useState<AdminPermissionOption[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [selectedPermissions, setSelectedPermissions] = useState<string[]>([]);
  const [form, setForm] = useState({ code: '', name: '', department: '' });
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const load = (preferredId = selectedId) => Promise.all([
    api<AdminPositionOption[]>('/admin/positions'),
    api<AdminUserOptions>('/admin/users/options')
  ]).then(([positionRows, options]) => {
    setPositions(positionRows);
    setPermissions(options.permissions);
    const active = positionRows.filter(position => position.active);
    const selected = active.find(position => String(position.id) === preferredId) ?? active[0];
    setSelectedId(selected ? String(selected.id) : '');
    setSelectedPermissions(selected?.permissions ?? []);
  }).catch(err => setError(err instanceof Error ? err.message : 'Unable to load job titles.'));
  useEffect(() => { load(); }, []);
  const groups = permissions.reduce((result, permission) => {
    const group = result.get(permission.module) ?? [];
    group.push(permission);
    result.set(permission.module, group);
    return result;
  }, new Map<string, AdminPermissionOption[]>());
  function choosePosition(id: string) {
    setSelectedId(id);
    setSelectedPermissions(positions.find(position => String(position.id) === id)?.permissions ?? []);
  }
  async function createPosition(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true); setError(''); setMessage('');
    try {
      const created = await api<{ id: number }>('/admin/positions', {
        method: 'POST',
        body: JSON.stringify({ ...form, code: form.code.trim(), name: form.name.trim(), department: form.department.trim() || null })
      });
      setForm({ code: '', name: '', department: '' });
      await load(String(created.id));
      setMessage('Job title created. Configure its explicit permissions below.');
    } catch (err) { setError(err instanceof Error ? err.message : 'Unable to create job title.'); }
    finally { setBusy(false); }
  }
  async function savePermissions() {
    if (!selectedId) return;
    setBusy(true); setError(''); setMessage('');
    try {
      await api(`/admin/positions/${selectedId}/permissions`, {
        method: 'PUT', body: JSON.stringify(selectedPermissions)
      });
      setMessage('Job-title permissions saved.');
      await load();
      choosePosition(selectedId);
    } catch (err) { setError(err instanceof Error ? err.message : 'Unable to save job-title permissions.'); }
    finally { setBusy(false); }
  }
  function togglePermission(code: string, checked: boolean) {
    setSelectedPermissions(current => checked ? [...new Set([...current, code])] : current.filter(value => value !== code));
  }
  return <div className="config-workspace">
    <form className="panel form-grid" onSubmit={createPosition}>
      <div className="wide"><p className="eyebrow">DESIGNATION CATALOG</p><h3>Add a job title</h3></div>
      <label>Code<input required maxLength={50} value={form.code} onChange={event => setForm(current => ({ ...current, code: event.target.value }))} /></label>
      <label>Title<input required maxLength={100} value={form.name} onChange={event => setForm(current => ({ ...current, name: event.target.value }))} /></label>
      <label>Department<input maxLength={100} value={form.department} onChange={event => setForm(current => ({ ...current, department: event.target.value }))} /></label>
      <div className="wide"><button className="primary" disabled={busy}>{busy ? 'Saving…' : 'Add job title'}</button></div>
    </form>
    <section className="panel">
      <div className="panel-head"><div><p className="eyebrow">EXPLICIT RIGHTS</p><h3>Designation permissions</h3></div>
        <button type="button" className="secondary" onClick={() => void load()}>Refresh</button></div>
      <label>Job title<select value={selectedId} onChange={event => choosePosition(event.target.value)}><option value="">Select a job title</option>{positions.filter(position => position.active).map(position => <option key={position.id} value={position.id}>{position.name} · {position.department || 'No department'}</option>)}</select></label>
      {groups.size > 0 && <div className="permission-catalog">{[...groups.entries()].map(([module, rows]) =>
        <fieldset className="permission-group" key={module}><legend>{module}</legend><div className="permission-options">{rows.map(permission =>
          <label className="permission-option" key={permission.code}><input type="checkbox" checked={selectedPermissions.includes(permission.code)} onChange={event => togglePermission(permission.code, event.target.checked)} /><span>{permission.action.replace(/_/g, ' ')}</span></label>
        )}</div></fieldset>)}</div>}
      <button type="button" className="primary" disabled={busy || !selectedId} onClick={() => void savePermissions()}>{busy ? 'Saving…' : 'Save permissions'}</button>
    </section>
    {message && <div className="success">{message}</div>}{error && <div className="error">{error}</div>}
  </div>;
}

function CustomerDataImport() {
  const [files, setFiles] = useState<File[]>([]);
  const [preview, setPreview] = useState<CustomerImportPreview | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  async function validateFiles() {
    if (!files.length) return;
    setLoading(true); setError(''); setPreview(null);
    const body = new FormData();
    files.forEach(file => body.append('files', file, file.name));
    try {
      const token = sessionStorage.getItem('webv9_token');
      const response = await fetch(`${API}/admin/customer-import/preview`, {
        method: 'POST', body, credentials: 'include',
        headers: token ? { Authorization: `Bearer ${token}` } : {}
      });
      const result = await response.json().catch(() => null);
      if (!response.ok) throw new Error(result?.message || 'The source files could not be previewed.');
      if (!result || typeof result !== 'object' || !result.totals || !Array.isArray(result.records)) {
        throw new Error('The preview endpoint returned no validation results. Check the backend version and try again.');
      }
      setPreview(result as CustomerImportPreview);
    } catch (err) { setError(err instanceof Error ? err.message : 'The source files could not be previewed.'); }
    finally { setLoading(false); }
  }
  const totals = preview?.totals;
  const statusCounts: [string, number][] = [
    ['Total source records', preview?.records.length ?? 0],
    ['Existing / Match', totals?.existingMatch ?? 0], ['New', totals?.newRecords ?? 0], ['Duplicate', totals?.duplicates ?? 0],
    ['Mismatch', totals?.mismatches ?? 0], ['Missing required information', totals?.missingRequiredInformation ?? 0],
    ['Cannot be mapped', totals?.cannotBeMapped ?? 0]
  ];
  const columns: [string, keyof CustomerImportRecord][] = [
    ['REG No.','regNo'], ['MBR No.','mbrNo'], ['Customer Name','customerName'],
    ['Matching status','classification'], ['Reason','databaseComparison']
  ];
  return <div className="customer-import">
    <div className="import-safety"><ShieldCheck size={18} /><div><strong>Preview / Validation Only</strong><span>NO database changes have been made. Source files are matched read-only against district_one. Import is unavailable pending approval.</span></div></div>
    <div className="panel import-upload"><div><p className="eyebrow">SOURCE FILES</p><h3>Select customer source files</h3><p className="muted">Choose one or more .xlsx or .csv files. REG No. is the primary matching key; MBR No. remains the member identifier.</p></div>
      <label className="import-file-picker">Select .xlsx / .csv files<input type="file" accept=".xlsx,.csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv" multiple onChange={event => { setFiles(Array.from(event.target.files ?? [])); setPreview(null); setError(''); }} /></label>
      {files.length > 0 && <ul className="import-file-list">{files.map(file => <li key={`${file.name}-${file.lastModified}`}>{file.name}<span>{(file.size / 1024).toFixed(0)} KB</span></li>)}</ul>}
      {error && <div className="error page-error">{error}</div>}
      <form className="import-actions" onSubmit={event => { event.preventDefault(); void validateFiles(); }}><button type="submit" className="primary" disabled={!files.length || loading}>{loading ? 'Validating…' : 'Preview & validate'}</button><button type="button" className="secondary" disabled title="Import will be enabled only after approval">Import — Coming After Approval</button></form>
    </div>
    {preview && <>
      <div className="import-result-meta">Reviewed {preview.sourceRowsReviewed.toLocaleString()} source rows across {preview.filesReviewed} file(s), grouped by REG No. into {preview.recordGroups.toLocaleString()} preview records.</div>
      <div className="import-count-grid">{statusCounts.map(([label, count]) => <div className="import-count" key={label}><span>{label}</span><strong>{count ?? 0}</strong></div>)}</div>
      <section className="panel import-preview-panel"><div className="panel-head"><div><p className="eyebrow">PROPOSED MAPPING</p><h3>Customer record preview</h3></div><span className="muted">Read-only database comparison</span></div>
        {preview.records.length === 0 ? <div className="empty">No customer records were found in the selected source files.</div> : <div className="table-wrap import-table-wrap"><table className="import-table"><thead><tr>{columns.map(([label]) => <th key={label}>{label}</th>)}</tr></thead><tbody>{preview.records.map((record, index) => <tr key={`${record.regNo ?? record.sourceRows}-${index}`}>{columns.map(([_, key]) => <td key={key}>{key === 'classification' ? <span className={`import-status ${record.classification.toLowerCase().replace(/[^a-z0-9]+/g,'-')}`}>{record.classification}</span> : record[key] || '—'}</td>)}</tr>)}</tbody></table></div>}
      </section>
    </>}
  </div>;
}

type ConfigField = [key: string, label: string, type: string];
type ConfigDefinition = { kind: string; key: string; columns: string[]; fields: ConfigField[]; required: string[] };
const configMap: Record<string, ConfigDefinition> = {
  'Charges Setup': { kind: 'charges', key: 'id', columns: ['code','name','description','amount','calculationMethod','active'], fields: [['code','Code','text'],['name','Name','text'],['description','Description','text'],['amount','Amount','number'],['calculationMethod','Calculation method','text']], required: ['code','name','amount','calculationMethod'] },
  'Bank Setup': { kind: 'banks', key: 'id', columns: ['bankName','accountTitle','accountNo','branch','bankCode','active'], fields: [['bankName','Bank name','text'],['accountTitle','Account title','text'],['accountNo','Account number','text'],['branch','Branch','text'],['bankCode','Bank code','text']], required: ['bankName','accountTitle','accountNo'] },
  'Cash Site Setup': { kind: 'cash-sites', key: 'id', columns: ['siteName','location','contactDetails','active'], fields: [['siteName','Site name','text'],['location','Location','text'],['contactDetails','Contact/details','text']], required: ['siteName'] },
  'Instalment Plan Setup': { kind: 'plans', key: 'id', columns: ['planCode','name','category','sizeMarla','numberOfInstalments','frequency','advancePercentage','instalmentAmount','intervalDays','startRule','active'], fields: [['name','Plan name','text'],['category','Plot/category','text'],['sizeMarla','Size (marla)','number'],['numberOfInstalments','Number of instalments','number'],['frequency','Frequency','text'],['advancePercentage','Advance percentage','number'],['instalmentAmount','Instalment amount','number'],['intervalDays','Interval days','number'],['startRule','Start rule','text']], required: ['name','frequency','numberOfInstalments','instalmentAmount','intervalDays'] },
  'Surcharge Setup': { kind: 'surcharges', key: 'id', columns: ['name','chargeName','method','rate','fixedAmount','graceDays','effectiveFrom','active'], fields: [['name','Rule name','text'],['chargeTypeId','Charge type','select'],['method','Calculation method','text'],['rate','Percentage rate','number'],['fixedAmount','Fixed amount','number'],['graceDays','Grace period days','number'],['effectiveFrom','Effective date','date']], required: ['name','chargeTypeId','method','effectiveFrom'] },
  'System Settings': { kind: 'settings', key: 'settingKey', columns: ['settingKey','settingValue','description','active','updatedAt'], fields: [['settingKey','Setting key','text'],['settingValue','Setting value','text'],['description','Description','text']], required: ['settingKey','settingValue'] }
};

function activeValue(value: unknown): boolean {
  if (typeof value === 'boolean') return value;
  if (typeof value === 'number') return value !== 0;
  return ['1','true','yes','active'].includes(String(value ?? '').trim().toLowerCase());
}

function ConfigWorkspace({ module }: { module: string }) {
  const config = configMap[module];
  const [rows, setRows] = useState<Record<string, unknown>[]>([]);
  const [schedules, setSchedules] = useState<Record<string, unknown>[]>([]);
  const [chargeTypes, setChargeTypes] = useState<Record<string, unknown>[]>([]);
  const [form, setForm] = useState<Record<string, string | boolean>>({ active: true });
  const [editing, setEditing] = useState<string | number | null>(null);
  const [loading, setLoading] = useState(true); const [saving, setSaving] = useState(false);
  const [error, setError] = useState(''); const [message, setMessage] = useState('');
  const load = () => { setLoading(true); setError(''); api<Record<string, unknown>[]>(`/admin/config/${config.kind}`).then(setRows).catch(e => setError(e instanceof Error ? e.message : 'Unable to load configuration.')).finally(() => setLoading(false)); };
  useEffect(() => { load(); if (config.kind === 'surcharges') api<Record<string, unknown>[]>('/admin/config/charges').then(setChargeTypes).catch(e => setError(e instanceof Error ? e.message : 'Unable to load charge types.')); if (config.kind === 'plans') api<Record<string, unknown>[]>('/admin/config/plans/schedules').then(setSchedules).catch(e => setError(e instanceof Error ? e.message : 'Unable to load instalment schedules.')); }, [config.kind]);
  const clear = () => { setEditing(null); setForm({ active: true }); setMessage(''); };
  function edit(row: Record<string, unknown>) {
    const key = row[config.key];
    if (key == null) { setError(`This ${module.toLowerCase()} record has no ${config.key} identifier.`); return; }
    const next = Object.fromEntries(Object.entries(row).map(([name, value]) => [name, name === 'active' ? activeValue(value) : String(value ?? '')])) as Record<string, string | boolean>;
    setEditing(typeof key === 'number' ? key : String(key)); setForm({ ...next, active: activeValue(row.active) }); setError(''); setMessage('');
  }
  async function submit(e: React.FormEvent) {
    e.preventDefault(); setSaving(true); setError(''); setMessage('');
    const isEdit = editing !== null;
    const routeKey = isEdit ? (config.kind === 'settings' ? encodeURIComponent(String(editing)) : String(editing)) : '';
    const path = `/admin/config/${config.kind}${isEdit ? `/${routeKey}` : ''}`;
    try { await api(path, { method: isEdit ? 'PUT' : 'POST', body: JSON.stringify(form) }); clear(); setMessage(`${module} saved successfully.`); load(); }
    catch (err) { setError(err instanceof Error ? err.message : `Unable to save ${module.toLowerCase()}.`); }
    finally { setSaving(false); }
  }
  async function toggle(row: Record<string, unknown>) {
    const key = row[config.key]; if (key == null) return;
    const routeKey = config.kind === 'settings' ? encodeURIComponent(String(key)) : String(key);
    setError(''); setMessage('');
    try { await api(`/admin/config/${config.kind}/${routeKey}/status`, { method: 'PATCH', body: JSON.stringify({ active: !activeValue(row.active) }) }); load(); }
    catch (err) { setError(err instanceof Error ? err.message : 'Unable to update status.'); }
  }
  return <div className="config-workspace">
    <div className="panel"><div className="panel-head"><div><p className="eyebrow">CONFIGURATION WORKSPACE</p><h3>{editing !== null ? `Edit ${module}` : module}</h3></div><button type="button" className="secondary" onClick={load}><RefreshCw size={15} /> Refresh</button></div>
      <form className="form-grid config-form" onSubmit={submit}>{config.fields.map(([key,label,type]) => <label key={key}>{label}{type === 'select' && key === 'chargeTypeId' ? <select required value={String(form[key] ?? '')} onChange={e => setForm(current => ({ ...current, [key]: e.target.value }))}><option value="">Select charge type</option>{chargeTypes.map(charge => <option key={String(charge.id)} value={String(charge.id)}>{String(charge.name)}{activeValue(charge.active) ? '' : ' (Inactive)'}</option>)}</select> : <input required={config.required.includes(key)} min={type === 'number' ? '0' : undefined} step={type === 'number' ? 'any' : undefined} type={type === 'select' ? 'text' : type} readOnly={config.kind === 'settings' && key === 'settingKey' && editing !== null} value={String(form[key] ?? '')} onChange={e => setForm(current => ({ ...current, [key]: e.target.value }))} />}</label>)}<label className="status-check"><input type="checkbox" checked={activeValue(form.active)} onChange={e => setForm(current => ({ ...current, active: e.target.checked }))} /> Active</label><div className="wide modal-actions"><button type="button" className="secondary" onClick={clear}>Clear</button><button className="primary" disabled={saving}>{saving ? 'Saving…' : editing !== null ? 'Update configuration' : 'Save configuration'}</button></div></form>{message && <div className="success">{message}</div>}{error && <div className="error">{error}</div>}
    </div>
    <div className="panel"><div className="panel-head"><h3>Existing records</h3><span className="muted">{loading ? 'Loading…' : `${rows.length} record(s)`}</span></div>{loading ? <div className="empty">Loading records…</div> : rows.length === 0 ? <div className="empty">No records found.</div> : <div className="table-wrap"><table><thead><tr>{config.columns.map(column => <th key={column}>{column.replace(/([A-Z])/g, ' $1')}</th>)}<th>Actions</th></tr></thead><tbody>{rows.map((row,index) => { const key = row[config.key]; const isActive = activeValue(row.active); return <tr key={String(key ?? index)}>{config.columns.map(column => <td key={column}>{column === 'active' ? <span className="badge">{isActive ? 'ACTIVE' : 'INACTIVE'}</span> : String(row[column] ?? '—')}</td>)}<td>{key != null && <><button type="button" className="secondary compact" onClick={() => edit(row)}><Pencil size={13} /> Edit</button> <button type="button" className="secondary compact" onClick={() => toggle(row)}>{isActive ? 'Deactivate' : 'Activate'}</button></>}</td></tr>; })}</tbody></table></div>}</div>
    {config.kind === 'plans' && <div className="panel"><div className="panel-head"><div><p className="eyebrow">BOOKING SCHEDULES</p><h3>Existing instalment schedules</h3></div><span className="muted">{schedules.length} schedule(s)</span></div><p className="muted">Schedules are stored against bookings in the existing database. Plan settings above configure instalment count, frequency, interval, amount and start rule.</p>{schedules.length === 0 ? <div className="empty">No booking-linked schedules found.</div> : <div className="table-wrap"><table><thead><tr>{['bookingNo','memberNo','memberName','instalmentNo','dueDate','amount','status'].map(column => <th key={column}>{column.replace(/([A-Z])/g,' $1')}</th>)}</tr></thead><tbody>{schedules.map((row,index) => <tr key={String(row.id ?? index)}>{['bookingNo','memberNo','memberName','instalmentNo','dueDate','amount','status'].map(column => <td key={column}>{String(row[column] ?? '—')}</td>)}</tr>)}</tbody></table></div>}</div>}
  </div>;
}

function AdminUsersList({ onEdit }: { onEdit: (id: number) => void }) {
  const [rows, setRows] = useState<AdminUserRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [busyId, setBusyId] = useState<number | null>(null);
  const load = () => {
    setLoading(true); setError('');
    api<AdminUserRecord[]>('/admin/users').then(setRows)
      .catch(e => setError(e instanceof Error ? e.message : 'Unable to load users.'))
      .finally(() => setLoading(false));
  };
  useEffect(() => { load(); }, []);
  async function toggle(row: AdminUserRecord) {
    setBusyId(row.id); setError('');
    try {
      await api(`/admin/users/${row.id}/status`, { method: 'PATCH', body: JSON.stringify({ active: !row.active }) });
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unable to update user status.');
    } finally { setBusyId(null); }
  }
  const canEdit = canPerformAction('Users & Roles', 'EDIT');
  return <div className="panel">
    <div className="panel-head"><div><p className="eyebrow">ACCESS REGISTER</p><h3>Current users</h3></div>
      <button className="secondary" onClick={load}><RefreshCw size={15} /> Refresh</button></div>
    {error && <div className="error page-error">{error}</div>}
    {loading ? <div className="empty">Loading users…</div> : <div className="table-wrap"><table>
      <thead><tr>{['User name','Display name','Email','Role','Status','Last login','Actions'].map(label => <th key={label}>{label}</th>)}</tr></thead>
      <tbody>{rows.map(row => <tr key={row.id}><td>{row.username}</td><td>{row.displayName}</td><td>{row.email}</td>
        <td>{row.role}</td><td><span className="badge">{row.active ? 'ACTIVE' : 'INACTIVE'}</span></td>
        <td>{row.lastLogin ? String(row.lastLogin).replace('T',' ').slice(0,19) : '—'}</td><td>
          {canEdit && <><button className="secondary compact" onClick={() => onEdit(row.id)}><Pencil size={13} /> Edit</button>
            <button className="secondary compact" disabled={busyId === row.id} onClick={() => toggle(row)}>
              {busyId === row.id ? 'Saving…' : row.active ? 'Deactivate' : 'Activate'}
            </button></>}
        </td></tr>)}</tbody>
    </table></div>}
  </div>;
}

function adminPasswordPolicyError(password: string): string | null {
  if (password.length < 8) return 'Password must contain at least 8 characters.';
  if (new TextEncoder().encode(password).length > 72) return 'Password must be no longer than 72 UTF-8 bytes.';
  if (!/[A-Za-z]/.test(password) || !/[0-9]/.test(password) || !/[^A-Za-z0-9]/.test(password)) {
    return 'Password must include a letter, a number, and a special character.';
  }
  const normalized = password.toLowerCase().replace(/[^a-z0-9]/g, '');
  if (['password', 'admin', 'districtone', 'welcome', 'letmein', 'qwerty', 'changeme', 'default', 'iloveyou', 'sunshine', 'abc123', '123456', '012345', 'abcdef', 'asdfgh', 'zxcvbn', 'passw0rd', 'administrator', 'football', 'baseball', 'dragon', 'monkey', 'shadow', 'master', 'secret', 'login', 'freedom', 'trustno1', 'starwars', 'summer', 'winter', 'spring', 'autumn', 'computer', 'manager', 'company'].some(value => normalized.includes(value)) || /(.)\1{5,}/.test(normalized)) {
    return 'Choose a less common password without predictable words or sequences.';
  }
  return null;
}

function AdminUserForm({ userId, actorIsSuperAdmin, onClose, onSaved }: { userId: number | null; actorIsSuperAdmin: boolean; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState({ username: '', displayName: '', email: '', contact: '', department: '', role: '', password: '', confirmPassword: '', active: false });
  const [access, setAccess] = useState({ positionId: '', startsAt: '', endsAt: '', projectIds: [] as number[] });
  const [options, setOptions] = useState<AdminUserOptions | null>(null); const [permissions, setPermissions] = useState<string[]>([]); const [error, setError] = useState(''); const [saving, setSaving] = useState(false); const [loadingUser, setLoadingUser] = useState(userId !== null);
  useEffect(() => {
    Promise.all([api<AdminUserOptions>('/admin/users/options'), ...(userId === null ? [] : [api<AdminUserRecord>(`/admin/users/${userId}`)])]).then(([result, user]) => {
      setOptions(result);
      if (user) {
        setForm({ username: user.username, displayName: user.displayName, email: user.email, contact: user.contact ?? user.mobile ?? '', department: user.department ?? '', role: user.role, password: '', confirmPassword: '', active: user.active });
        setAccess({
          positionId: user.positionId ? String(user.positionId) : '',
          startsAt: user.accessStartsAt?.slice(0, 16) ?? '',
          endsAt: user.accessEndsAt?.slice(0, 16) ?? '',
          projectIds: (user.projectScopes ?? []).map(scope => scope.projectId)
        });
        setPermissions(user.permissions ?? []);
      } else {
        const initialRole = result.roles.find(role => role.code === 'DATA_ENTRY') ?? result.roles[0];
        const initialPosition = result.positions.find(position => position.code === 'DATA_ENTRY' && position.active)
          ?? result.positions.find(position => position.active);
        if (initialRole) setForm(current => ({ ...current, role: initialRole.code }));
        if (initialPosition) {
          setAccess(current => ({ ...current, positionId: String(initialPosition.id) }));
          if (initialPosition.department) setForm(current => ({ ...current, department: initialPosition.department ?? '' }));
          setPermissions(initialPosition.permissions);
        }
      }
    }).catch(err => setError(err instanceof Error ? err.message : 'Unable to load roles and permissions.')).finally(() => setLoadingUser(false));
  }, [userId]);
  function update(key: keyof typeof form, value: string | boolean) { setForm(current => ({ ...current, [key]: value })); }
  function changeRole(code: string) { update('role', code); }
  function changePosition(id: string) {
    setAccess(current => ({ ...current, positionId: id }));
    const position = options?.positions.find(item => String(item.id) === id);
    if (position?.department) update('department', position.department);
    setPermissions(position?.permissions ?? []);
  }
  function togglePermission(code: string, checked: boolean) { setPermissions(current => checked ? [...new Set([...current, code])] : current.filter(item => item !== code)); }
  function toggleProject(projectId: number, checked: boolean) {
    setAccess(current => ({ ...current, projectIds: checked
      ? [...new Set([...current.projectIds, projectId])]
      : current.projectIds.filter(id => id !== projectId) }));
  }
  const groupedPermissions = options?.permissions.reduce((groups, permission) => {
    const group = groups.get(permission.module) ?? [];
    group.push(permission);
    groups.set(permission.module, group);
    return groups;
  }, new Map<string, AdminPermissionOption[]>()) ?? new Map<string, AdminPermissionOption[]>();
  const editing = userId !== null;
  const canSave = canPerformAction('Users & Roles', editing ? 'EDIT' : 'ADD');
  const projectScopes = access.projectIds.map(projectId => {
    const project = options?.projects.find(item => item.id === projectId);
    return project ? { projectId, location: project.location ?? null, city: project.city ?? null } : null;
  }).filter((scope): scope is { projectId: number; location: string | null; city: string | null } => scope !== null);
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const changingPassword = !editing || form.password.length > 0;
    if (changingPassword) {
      const policyError = adminPasswordPolicyError(form.password);
      if (policyError) { setError(policyError); return; }
      if (form.password !== form.confirmPassword) { setError('Passwords do not match.'); return; }
    }
    if (!access.positionId && !(actorIsSuperAdmin && form.role === 'SUPER_ADMIN')) {
      setError('Select a configured job title for this account.');
      return;
    }
    if (!actorIsSuperAdmin && projectScopes.length === 0) {
      setError('Assign at least one authorized project/location.');
      return;
    }
    setSaving(true); setError('');
    try {
      const payload = {
        ...form,
        password: form.password || null,
        confirmPassword: changingPassword ? form.confirmPassword : null,
        username: form.username.trim(),
        displayName: form.displayName.trim(),
        email: form.email.trim(),
        permissions,
        positionId: access.positionId ? Number(access.positionId) : null,
        accessStartsAt: access.startsAt || null,
        accessEndsAt: access.endsAt || null,
        projectScopes
      };
      await api(userId === null ? '/admin/users' : `/admin/users/${userId}`, {
        method: userId === null ? 'POST' : 'PUT', body: JSON.stringify(payload)
      });
      onSaved(); onClose();
    } catch (err) { setError(err instanceof Error ? err.message : 'Unable to save user.'); }
    finally { setSaving(false); }
  }
  return <div className="modal-backdrop"><form className="modal registration-modal" onSubmit={submit}><div className="modal-head"><div><p className="eyebrow">USER ADMINISTRATION</p><h2>{editing ? 'Edit User' : 'Create Users'}</h2></div><button type="button" className="icon" onClick={onClose}><X /></button></div><div className="form-grid">
    <label>User Name<input required maxLength={80} autoComplete="username" value={form.username} onChange={e => update('username', e.target.value)} /></label>
    <label>Display Name<input required maxLength={150} autoComplete="name" value={form.displayName} onChange={e => update('displayName', e.target.value)} /></label>
    <label>Email<input required maxLength={180} type="email" autoComplete="email" value={form.email} onChange={e => update('email', e.target.value)} /></label>
    <label>Mobile<input maxLength={40} value={form.contact} onChange={e => update('contact', e.target.value)} /></label>
    <label>Department<input maxLength={100} value={form.department} onChange={e => update('department', e.target.value)} /></label>
    <label>Role<select required value={form.role} onChange={e => changeRole(e.target.value)} disabled={!options?.roles.length}><option value="">Select role</option>{options?.roles.map(role => <option key={role.code} value={role.code}>{role.name}</option>)}</select></label>
    <label>Job title / designation<select value={access.positionId} onChange={e => changePosition(e.target.value)}><option value="">Select job title</option>{options?.positions.filter(position => position.active || String(position.id) === access.positionId).map(position => <option key={position.id} value={position.id}>{position.name}{position.active ? '' : ' (Inactive)'}</option>)}</select><small>Designation permissions and department determine effective access; the role is retained for compatibility.</small></label>
    <label>Access starts at<input type="datetime-local" value={access.startsAt} onChange={e => setAccess(current => ({ ...current, startsAt: e.target.value }))} /></label>
    <label>Access ends at<input type="datetime-local" value={access.endsAt} onChange={e => setAccess(current => ({ ...current, endsAt: e.target.value }))} /></label>
    <fieldset className="permission-group wide"><legend>Authorized project / location scopes</legend><div className="permission-options">{options?.projects.map(project => <label className="permission-option" key={project.id}><input type="checkbox" checked={access.projectIds.includes(project.id)} onChange={e => toggleProject(project.id, e.target.checked)} /><span>{project.projectName} · {project.location || '—'} / {project.city || '—'}</span></label>)}</div>{!options?.projects.length && <small>No active projects are available.</small>}</fieldset>
    <label>Password<input required={!editing} minLength={8} maxLength={72} type="password" autoComplete="new-password" value={form.password} onChange={e => update('password', e.target.value)} />{editing && <small>Leave blank to keep the current password.</small>}<small>Use at least 8 characters with a letter, a number, and a special character. Common passwords are rejected.</small></label><label>Confirm password<input required={!editing || form.password.length > 0} minLength={8} maxLength={72} type="password" autoComplete="new-password" value={form.confirmPassword} onChange={e => update('confirmPassword', e.target.value)} /></label>
    <label className="wide">Permissions{!options ? <div className="permission-loading">Loading configured permissions…</div> : <div className="permission-catalog">{[...groupedPermissions.entries()].map(([module, items]) => <fieldset className="permission-group" key={module}><legend>{module}</legend><div className="permission-options">{items.map(permission => <label className="permission-option" key={permission.code}><input type="checkbox" checked={permissions.includes(permission.code)} onChange={e => togglePermission(permission.code, e.target.checked)} /><span>{permission.action.replace(/_/g, ' ')}</span></label>)}</div></fieldset>)}</div>}</label>
    <label className="wide status-check"><input type="checkbox" checked={form.active} onChange={e => update('active', e.target.checked)} /> Active account <small>{form.active ? 'Active' : 'Inactive'}</small></label>
  </div>{error && <div className="error">{error}</div>}{loadingUser && <div className="permission-loading">Loading user and permissions…</div>}<div className="modal-actions"><button type="button" className="secondary" onClick={onClose}>Cancel</button><button className="primary" disabled={saving || loadingUser || !options?.roles.length || !canSave}>{saving ? 'Saving…' : editing ? 'Save changes' : 'Create user'}</button></div></form></div>;
}
function ModulePage({ title }: { title: string }) { return <div className="empty-page"><div className="empty-icon"><Settings /></div><p className="eyebrow">{title.toUpperCase()}</p><h1>{title}</h1><p className="muted">No operational records are currently available for this module.</p></div>; }
createRoot(document.getElementById('root')!).render(<App />);
