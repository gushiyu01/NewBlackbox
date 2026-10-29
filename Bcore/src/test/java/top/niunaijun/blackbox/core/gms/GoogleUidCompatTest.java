package top.niunaijun.blackbox.core.gms;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class GoogleUidCompatTest {

    @Test
    public void addsGoogleComponentsToTheCaller() {
        String[] resolved = {"com.facebook.aura"};
        String[] merged = GoogleUidCompat.withGoogleComponents(
                resolved, Arrays.asList("com.google.android.gms", "com.android.vending"));
        assertArrayEquals(new String[]{
                "com.facebook.aura", "com.google.android.gms", "com.android.vending"}, merged);
    }

    @Test
    public void returnsInputWhenNothingIsInstalled() {
        String[] resolved = {"com.facebook.aura"};
        assertSame(resolved, GoogleUidCompat.withGoogleComponents(resolved, Collections.emptyList()));
    }

    @Test
    public void doesNotDuplicatePackages() {
        String[] resolved = {"com.google.android.gms"};
        String[] merged = GoogleUidCompat.withGoogleComponents(
                resolved, Collections.singletonList("com.google.android.gms"));
        assertArrayEquals(resolved, merged);
    }

    @Test
    public void leavesEmptyResolutionAlone() {
        String[] resolved = {};
        assertSame(resolved, GoogleUidCompat.withGoogleComponents(
                resolved, Collections.singletonList("com.google.android.gms")));
    }
}
