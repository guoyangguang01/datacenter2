import { useEffect } from 'react';
import type { ReactNode } from 'react';
import { BrowserRouter, Routes, Route, Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { Button, Layout, Menu, Select, Space } from 'antd';
import { AppstoreOutlined, DashboardOutlined, LogoutOutlined, NodeIndexOutlined, SettingOutlined } from '@ant-design/icons';
import SdnLogo from './components/SdnLogo';
import BusinessPage from './pages/BusinessPage';
import ChannelPage from './pages/ChannelPage';
import PointPage from './pages/PointPage';
import DashboardPage from './pages/DashboardPage';
import MonitorPage from './pages/MonitorPage';
import LoginPage from './pages/LoginPage';
import { authUtil } from './utils/auth';
import { wsService } from './services/websocket';
import { useBusinessStore } from './stores/businessStore';

const { Header, Content, Sider } = Layout;

const menuItems = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: <Link to="/dashboard">仪表盘</Link> },
  { key: '/businesses', icon: <AppstoreOutlined />, label: <Link to="/businesses">业务管理</Link> },
  { key: '/channels', icon: <NodeIndexOutlined />, label: <Link to="/channels">通道</Link> },
  { key: '/points', icon: <SettingOutlined />, label: <Link to="/points">测点</Link> },
];

function ProtectedRoute({ children }: { children: ReactNode }) {
  if (!authUtil.isAuthenticated()) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

function BusinessSwitcher() {
  const businesses = useBusinessStore((s) => s.businesses);
  const currentBusinessId = useBusinessStore((s) => s.currentBusinessId);
  const setCurrentBusiness = useBusinessStore((s) => s.setCurrentBusiness);

  return (
    <Select
      style={{ width: 220 }}
      placeholder="请先创建业务"
      value={currentBusinessId ?? undefined}
      onChange={setCurrentBusiness}
      options={businesses.map((b) => ({ value: b.businessId, label: b.businessName }))}
    />
  );
}

function AppLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const fetchBusinesses = useBusinessStore((s) => s.fetchBusinesses);

  useEffect(() => {
    fetchBusinesses();
  }, [fetchBusinesses]);

  const handleLogout = () => {
    wsService.disconnect();
    authUtil.clearToken();
    navigate('/login', { replace: true });
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider collapsible>
        <div style={{ height: 32, margin: 16, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8 }}>
          <SdnLogo size={28} />
          <span style={{ color: 'white', fontWeight: 'bold', fontSize: 14 }}>SDNCustom</span>
        </div>
        <Menu
          theme="dark"
          selectedKeys={[location.pathname]}
          mode="inline"
          items={menuItems}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            padding: '0 16px',
            background: '#fff',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <h1 style={{ margin: 0, fontSize: 18 }}>物联网数据中心</h1>
          <Space>
            <BusinessSwitcher />
            <span>{authUtil.getUsername()}</span>
            <Button type="text" icon={<LogoutOutlined />} onClick={handleLogout}>
              退出登录
            </Button>
          </Space>
        </Header>
        <Content style={{ margin: 16, padding: 24, background: '#fff', borderRadius: 8 }}>
          <Routes>
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route path="/businesses" element={<BusinessPage />} />
            <Route path="/channels" element={<ChannelPage />} />
            <Route path="/points" element={<PointPage />} />
            <Route path="/monitor" element={<MonitorPage />} />
            <Route path="*" element={<DashboardPage />} />
          </Routes>
        </Content>
      </Layout>
    </Layout>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          path="/*"
          element={
            <ProtectedRoute>
              <AppLayout />
            </ProtectedRoute>
          }
        />
      </Routes>
    </BrowserRouter>
  );
}
