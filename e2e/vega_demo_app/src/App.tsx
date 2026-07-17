import React, {useEffect, useRef, useState, forwardRef} from 'react';
import {Pressable, Text, TextInput, View, StyleSheet} from 'react-native';
import {TVFocusGuideView} from '@amazon-devices/react-native-kepler';

// Mirrors the tvOS demo app (e2e/tvos_demo_app) — same screens, labels and testIDs —
// re-implemented for Vega (React Native 0.72 / Kepler), which cannot use the tvOS app's
// Expo + react-navigation + react-native-tvos stack. Navigation is plain state; the Back
// button stands in for the Apple TV menu key.

type Screen = 'Home' | 'Navigation' | 'TextInput' | 'Focus';

export const App = () => {
  const [screen, setScreen] = useState<Screen>('Home');
  const goHome = () => setScreen('Home');

  switch (screen) {
    case 'Navigation':
      return <NavigationScreen onBack={goHome} />;
    case 'TextInput':
      return <TextInputScreen onBack={goHome} />;
    case 'Focus':
      return <FocusScreen onBack={goHome} />;
    default:
      return <HomeScreen onNavigate={setScreen} />;
  }
};

// Home — vertical menu of buttons that navigate to each test screen.
const HomeScreen = ({onNavigate}: {onNavigate: (s: Screen) => void}) => (
  <View style={styles.screen}>
    <Text style={styles.title}>Home</Text>
    <TVFocusGuideView style={styles.column}>
      <CustomButton testID="menu-navigation" hasTVPreferredFocus onPress={() => onNavigate('Navigation')}>
        Navigation Test
      </CustomButton>
      <CustomButton testID="menu-text-input" onPress={() => onNavigate('TextInput')}>
        Text Input Test
      </CustomButton>
      <CustomButton testID="menu-focus" onPress={() => onNavigate('Focus')}>
        Focus Test
      </CustomButton>
    </TVFocusGuideView>
  </View>
);

// Navigation — 2x2 grid for testing directional D-pad navigation.
const NavigationScreen = ({onBack}: {onBack: () => void}) => (
  <View style={styles.screen}>
    <Text style={styles.title}>Navigation Test</Text>
    <TVFocusGuideView style={styles.column}>
      <View style={styles.row}>
        <CustomButton testID="grid-top-left" hasTVPreferredFocus style={styles.flex}>Top Left</CustomButton>
        <CustomButton testID="grid-top-right" style={styles.flex}>Top Right</CustomButton>
      </View>
      <View style={styles.row}>
        <CustomButton testID="grid-bottom-left" style={styles.flex}>Bottom Left</CustomButton>
        <CustomButton testID="grid-bottom-right" style={styles.flex}>Bottom Right</CustomButton>
      </View>
      <CustomButton testID="back-button" onPress={onBack}>Back</CustomButton>
    </TVFocusGuideView>
  </View>
);

// Text Input — text field + label showing the current text.
const TextInputScreen = ({onBack}: {onBack: () => void}) => {
  const [text, setText] = useState('');
  return (
    <View style={styles.screen}>
      <Text style={styles.title}>Text Input Test</Text>
      <TextInput
        testID="text-field"
        style={styles.textInput}
        value={text}
        onChangeText={setText}
        placeholder="Type here..."
        placeholderTextColor="#888"
        hasTVPreferredFocus
      />
      <Text style={styles.label} testID="typed-label">Typed: {text}</Text>
      <CustomButton testID="back-button" onPress={onBack}>Back</CustomButton>
    </View>
  );
};

// Focus — two buttons; Button 2 receives programmatic focus on mount.
const FocusScreen = ({onBack}: {onBack: () => void}) => {
  const secondButtonRef = useRef<View>(null);
  useEffect(() => {
    (secondButtonRef.current as any)?.requestTVFocus?.();
  }, []);
  return (
    <View style={styles.screen}>
      <Text style={styles.title}>Focus Test</Text>
      <TVFocusGuideView style={styles.column}>
        <CustomButton testID="focus-button-1">Button 1</CustomButton>
        <CustomButton testID="focus-button-2" ref={secondButtonRef} hasTVPreferredFocus>Button 2</CustomButton>
        <CustomButton testID="back-button" onPress={onBack}>Back</CustomButton>
      </TVFocusGuideView>
    </View>
  );
};

const CustomButton = forwardRef<View, any>(({children, onPress, style, testID, hasTVPreferredFocus}, ref) => {
  const [focused, setFocused] = useState(false);
  return (
    <Pressable
      ref={ref}
      testID={testID}
      accessible
      accessibilityRole="button"
      accessibilityLabel={typeof children === 'string' ? children : undefined}
      hasTVPreferredFocus={hasTVPreferredFocus}
      onPress={onPress}
      onFocus={() => setFocused(true)}
      onBlur={() => setFocused(false)}
      style={[styles.button, focused ? styles.buttonFocused : null, style]}>
      <Text style={styles.buttonText}>{children}</Text>
    </Pressable>
  );
});

const styles = StyleSheet.create({
  screen: {flex: 1, padding: 80, backgroundColor: '#000000'},
  title: {color: '#FFFFFF', fontSize: 90, fontWeight: 'bold', marginBottom: 60},
  column: {gap: 30},
  row: {flexDirection: 'row', gap: 30},
  flex: {flex: 1},
  button: {
    backgroundColor: '#00008B',
    borderWidth: 6,
    borderColor: 'transparent',
    paddingVertical: 30,
    paddingHorizontal: 50,
  },
  buttonFocused: {borderColor: '#FFFFFF', backgroundColor: '#0074B8'},
  buttonText: {color: '#FFFFFF', fontSize: 48},
  textInput: {
    borderWidth: 3,
    borderColor: '#444444',
    color: '#FFFFFF',
    fontSize: 48,
    padding: 24,
    marginBottom: 30,
    width: 1000,
  },
  label: {color: '#CCCCCC', fontSize: 48, marginBottom: 30},
});
