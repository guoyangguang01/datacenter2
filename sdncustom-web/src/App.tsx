import { BrowserRouter, Routes, Route, Link, useLocation } from 'react-router-dom';
import { Layout, Menu } from 'antd';
import { DashboardOutlined, NodeIndexOutlined, SettingOutlined } from '@ant-design/icons';
import ChannelPage from './pages/ChannelPage';
import PointPage from './pages/PointPage';
import DashboardPage from './pages/DashboardPage';
import MonitorPage from './pages/MonitorPage';

const { Header, Content, Sider } = Layout;

const menuItems = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: <Link to="/dashboard">仪表盘</Link> },
  { key: '/channels', icon: <NodeIndexOutlined />, label: <Link to="/channels">通道</Link> },
  { key: '/points', icon: <SettingOutlined />, label: <Link to="/points">测点</Link> },
];

function AppLayout() {
  const location = useLocation();

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider collapsible>
        <div style={{ height: 32, margin: 16, color: 'white', textAlign: 'center', fontWeight: 'bold' }}>
          SDNCustom
        </div>
        <Menu
          theme="dark"
          selectedKeys={[location.pathname]}
          mode="inline"
          items={menuItems}
        />
      </Sider>
      <Layout>
        <Header style={{ padding: '0 16px', background: '#fff', display: 'flex', alignItems: 'center' }}>
          <h1 style={{ margin: 0, fontSize: 18 }}>物联网数据中心</h1>
        </Header>
        <Content style={{ margin: 16, padding: 24, background: '#fff', borderRadius: 8 }}>
          <Routes>
            <Route path="/dashboard" element={<DashboardPage />} />
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
      <AppLayout />
    </BrowserRouter>
  );
}
