import './agent-demo/agent-demo.css';
import AgentDemoPage from './agent-demo/AgentDemoPage';

/**
 * SuperTrader-Demo frontend shell. A single-page app that routes to the Agent
 * Runtime Demo. No product modules, no trading UI — just the chat-first Agent
 * interaction Demo.
 */
export default function App() {
  // The hash router: default and only route is #/agent-demo.
  const hash = typeof window !== 'undefined' ? window.location.hash : '';
  const route = hash.split('?')[0];

  if (route === '#/agent-demo' || route === '' || route === '#/' || route === '#') {
    return <AgentDemoPage />;
  }
  // Unknown route → redirect to the demo.
  return (
    <div style={{ padding: 40, textAlign: 'center', fontFamily: 'sans-serif' }}>
      <p>未知路由：{route}</p>
      <a href="#/agent-demo">→ 进入 SuperTrader Demo</a>
    </div>
  );
}
