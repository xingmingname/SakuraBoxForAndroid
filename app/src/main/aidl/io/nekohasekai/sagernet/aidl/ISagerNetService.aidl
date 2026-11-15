package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback;

import android.os.ParcelFileDescriptor;

interface ISagerNetService {
  int getState();
  String getProfileName();

  void registerCallback(in ISagerNetServiceCallback cb, int id);
  oneway void unregisterCallback(in ISagerNetServiceCallback cb);

  int urlTest();
  boolean clearTraffic(in long[] profileIds);

  ParcelFileDescriptor openConnectionMonitor(String connectionId);
}
