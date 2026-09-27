import * as React from 'react';
import { Modal, StatusBar, StyleSheet, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { NavBar, type TabId } from './src/components/NavBar';
import { diagnosticsRequest, type DiagnosticsRequest } from './src/diagnosticsRequest';
import { AboutScreen } from './src/screens/AboutScreen';
import { DiagnosticsScreen } from './src/screens/DiagnosticsScreen';
import { HomeScreen } from './src/screens/HomeScreen';
import { LiveScreen } from './src/screens/LiveScreen';
import { UploadScreen } from './src/screens/UploadScreen';
import { theme } from './src/theme';

/**
 * Identical in both example apps, kept as copies so each proves its own install path. The camera
 * mounts only while its tab is selected, so leaving the tab releases it.
 */
export default function App() {
  const [tab, setTab] = React.useState<TabId>('home');
  const [diagnostics, setDiagnostics] = React.useState(false);
  const [about, setAbout] = React.useState(false);
  const [autoRun, setAutoRun] = React.useState<DiagnosticsRequest | null>(null);
  const live = tab === 'live';

  React.useEffect(() => {
    void diagnosticsRequest().then((request) => {
      if (!request) return;
      setAutoRun(request);
      setDiagnostics(true);
    });
  }, []);

  return (
    <SafeAreaProvider>
      <View style={styles.root}>
        {/* Dark glyphs on the light screens, light ones over the camera. */}
        <StatusBar
          barStyle={live ? 'light-content' : 'dark-content'}
          backgroundColor="transparent"
          translucent
        />

        {tab === 'home' ? (
          <HomeScreen
            onNavigate={setTab}
            onDiagnostics={() => setDiagnostics(true)}
            onAbout={() => setAbout(true)}
          />
        ) : null}
        {live ? <LiveScreen onClose={() => setTab('home')} /> : null}
        {tab === 'upload' ? <UploadScreen /> : null}

        {live ? null : <NavBar active={tab} onSelect={setTab} />}

        <Modal visible={about} animationType="slide" presentationStyle="fullScreen">
          <SafeAreaProvider>
            <AboutScreen onClose={() => setAbout(false)} />
          </SafeAreaProvider>
        </Modal>

        <Modal visible={diagnostics} animationType="slide" presentationStyle="fullScreen">
          <SafeAreaProvider>
            <View style={styles.root}>
              <DiagnosticsScreen onClose={() => setDiagnostics(false)} autoRun={autoRun} />
            </View>
          </SafeAreaProvider>
        </Modal>
      </View>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: theme.color.background,
  },
});
