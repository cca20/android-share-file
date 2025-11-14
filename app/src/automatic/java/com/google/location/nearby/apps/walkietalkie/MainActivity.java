package com.google.location.nearby.apps.walkietalkie;

import android.Manifest;
import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import android.text.SpannableString;
import android.text.format.DateFormat;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;
import android.content.ContentValues;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Our WalkieTalkie Activity. This Activity has 3 {@link State}s.
 *
 * <p>{@link State#UNKNOWN}: We cannot do anything while we're in this state. The app is likely in
 * the background.
 *
 * <p>{@link State#SEARCHING}: Our default state (after we've connected). We constantly listen for a
 * device to advertise near us, while simultaneously advertising ourselves.
 *
 * <p>{@link State#CONNECTED}: We've connected to another device and can now talk to them by holding
 * down the volume keys and speaking into the phone. Advertising and discovery have both stopped.
 */
public class MainActivity extends ConnectionsActivity {
  /** If true, debug logs are shown on the device. */
  private static final boolean DEBUG = true;

  /**
   * The connection strategy we'll use for Nearby Connections. In this case, we've decided on
   * P2P_STAR, which is a combination of Bluetooth Classic and WiFi Hotspots.
   */
  private static final Strategy STRATEGY = Strategy.P2P_STAR;

  /** Length of state change animations. */
  private static final long ANIMATION_DURATION = 600;

  private static final int READ_REQUEST_CODE = 42;

  /**
   * A set of background colors. We'll hash the authentication token we get from connecting to a
   * device to pick a color randomly from this list. Devices with the same background color are
   * talking to each other securely (with 1/COLORS.length chance of collision with another pair of
   * devices).
   */
  @ColorInt
  private static final int[] COLORS =
      new int[] {
        0xFFF44336 /* red */,
        0xFF9C27B0 /* deep purple */,
        0xFF00BCD4 /* teal */,
        0xFF4CAF50 /* green */,
        0xFFFFAB00 /* amber */,
        0xFFFF9800 /* orange */,
        0xFF795548 /* brown */
      };

  /**
   * This service id lets us find other nearby devices that are interested in the same thing. Our
   * sample does exactly one thing, so we hardcode the ID.
   */
  private static final String SERVICE_ID =
      "com.google.location.nearby.apps.walkietalkie.automatic.SERVICE_ID";

  /**
   * The state of the app. As the app changes states, the UI will update and advertising/discovery
   * will start/stop.
   */
  private State mState = State.UNKNOWN;

  /** A random UID used as this device's endpoint name. */
  private String mName;

  /**
   * The background color of the 'CONNECTED' state. This is randomly chosen from the {@link #COLORS}
   * list, based off the authentication token.
   */
  @ColorInt private int mConnectedColor = COLORS[0];

  /** Displays the previous state during animation transitions. */
  private TextView mPreviousStateView;

  /** Displays the current state. */
  private TextView mCurrentStateView;

  /** An animator that controls the animation from previous state to current state. */
  @Nullable private Animator mCurrentAnimator;

  /** A running log of debug messages. Only visible when DEBUG=true. */
  private TextView mDebugLogView;

  /** Listens to holding/releasing the volume rocker. */
  private final GestureDetector mGestureDetector =
      new GestureDetector(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP) {
        @Override
        protected void onHold() {
          logV("onHold");
          startRecording();
        }

        @Override
        protected void onRelease() {
          logV("onRelease");
          stopRecording();
        }
      };

  /** For recording audio as the user speaks. */
  @Nullable private AudioRecorder mRecorder;

  /** For playing audio from other users nearby. */
  @Nullable private AudioPlayer mAudioPlayer;

  /** The phone's original media volume. */
  private int mOriginalVolume;

  private final Map<Long, Payload> mReceivedFilePayloads = new HashMap<>();

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    getSupportActionBar()
        .setBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.actionBar));

    mPreviousStateView = (TextView) findViewById(R.id.previous_state);
    mCurrentStateView = (TextView) findViewById(R.id.current_state);

    mDebugLogView = (TextView) findViewById(R.id.debug_log);
    mDebugLogView.setVisibility(DEBUG ? View.VISIBLE : View.GONE);
    mDebugLogView.setMovementMethod(new ScrollingMovementMethod());

    mName = generateRandomName();

    ((TextView) findViewById(R.id.name)).setText(mName);

    Button shareButton = (Button) findViewById(R.id.share_button);
    shareButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        if (getConnectedEndpoints().isEmpty()) {
          Toast.makeText(MainActivity.this, "No connected devices", Toast.LENGTH_SHORT).show();
          return;
        }
        showImageChooser();
      }
    });
  }

  private void showImageChooser() {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("image/*");
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    startActivityForResult(intent, READ_REQUEST_CODE);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
      Uri uri = data.getData();
      if (uri != null) {
        if (getConnectedEndpoints().isEmpty()) {
          Toast.makeText(this, "No connected devices", Toast.LENGTH_SHORT).show();
          return;
        }
        if (getState() != State.CONNECTED) {
          Toast.makeText(this, "Not connected to any device", Toast.LENGTH_SHORT).show();
          return;
        }
        final Uri finalUri = uri;
        new Thread(new Runnable() {
          @Override
          @WorkerThread
          public void run() {
            ParcelFileDescriptor pfd = null;
            try {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                getContentResolver().takePersistableUriPermission(
                    finalUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
              }
              pfd = getContentResolver().openFileDescriptor(finalUri, "r");
              if (pfd != null) {
                final ParcelFileDescriptor finalPfd = pfd;
                Payload filePayload = Payload.fromFile(pfd);
                logD("Sending FILE payload, ID: " + filePayload.getId());
                final Payload finalPayload = filePayload;
                runOnUiThread(new Runnable() {
                  @Override
                  @UiThread
                  public void run() {
                    if (getConnectedEndpoints().isEmpty() || getState() != State.CONNECTED) {
                      logW("Connection lost while preparing file", null);
                      Toast.makeText(MainActivity.this, "Connection lost", Toast.LENGTH_SHORT).show();
                      try {
                        finalPfd.close();
                      } catch (IOException e) {
                        logE("Error closing file descriptor", e);
                      }
                      return;
                    }
                    send(finalPayload);
                    Toast.makeText(MainActivity.this, "Sending photo...", Toast.LENGTH_SHORT).show();
                  }
                });
              } else {
                runOnUiThread(new Runnable() {
                  @Override
                  public void run() {
                    logE("Failed to open file descriptor", null);
                    Toast.makeText(MainActivity.this, "Failed to open file", Toast.LENGTH_SHORT).show();
                  }
                });
              }
            } catch (FileNotFoundException e) {
              logE("File not found", e);
              runOnUiThread(new Runnable() {
                @Override
                public void run() {
                  Toast.makeText(MainActivity.this, "File not found", Toast.LENGTH_SHORT).show();
                }
              });
            } catch (Exception e) {
              logE("Error sending file", e);
              runOnUiThread(new Runnable() {
                @Override
                public void run() {
                  Toast.makeText(MainActivity.this, "Error sending file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
              });
            }
          }
        }).start();
      }
    }
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    if (mState == State.CONNECTED && mGestureDetector.onKeyEvent(event)) {
      return true;
    }
    return super.dispatchKeyEvent(event);
  }

  @Override
  protected void onStart() {
    super.onStart();

    // Set the media volume to max.
    setVolumeControlStream(AudioManager.STREAM_MUSIC);
    AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    mOriginalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
    audioManager.setStreamVolume(
        AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);

    if (getState() == State.UNKNOWN) {
      setState(State.SEARCHING);
    }
  }

  @Override
  protected void onStop() {
    // Restore the original volume.
    AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, mOriginalVolume, 0);
    setVolumeControlStream(AudioManager.USE_DEFAULT_STREAM_TYPE);

    // Stop all audio-related threads
    if (isRecording()) {
      stopRecording();
    }
    if (isPlaying()) {
      stopPlaying();
    }

    if (isFinishing()) {
      setState(State.UNKNOWN);
    }

    if (mCurrentAnimator != null && mCurrentAnimator.isRunning()) {
      mCurrentAnimator.cancel();
    }

    super.onStop();
  }

  @Override
  public void onBackPressed() {
    if (getState() == State.CONNECTED) {
      setState(State.SEARCHING);
      return;
    }
    super.onBackPressed();
  }

  @Override
  protected void onEndpointDiscovered(Endpoint endpoint) {
    // We found an advertiser!
    stopDiscovering();
    connectToEndpoint(endpoint);
  }

  @Override
  protected void onConnectionInitiated(Endpoint endpoint, ConnectionInfo connectionInfo) {
    // A connection to another device has been initiated! We'll use the auth token, which is the
    // same on both devices, to pick a color to use when we're connected. This way, users can
    // visually see which device they connected with.
    mConnectedColor = COLORS[connectionInfo.getAuthenticationToken().hashCode() % COLORS.length];

    // We accept the connection immediately.
    acceptConnection(endpoint);
  }

  @Override
  protected void onEndpointConnected(Endpoint endpoint) {
    Toast.makeText(
            this, getString(R.string.toast_connected, endpoint.getName()), Toast.LENGTH_SHORT)
        .show();
    setState(State.CONNECTED);
  }

  @Override
  protected void onEndpointDisconnected(Endpoint endpoint) {
    Toast.makeText(
            this, getString(R.string.toast_disconnected, endpoint.getName()), Toast.LENGTH_SHORT)
        .show();
    setState(State.SEARCHING);
  }

  @Override
  protected void onConnectionFailed(Endpoint endpoint) {
    logW("Connection failed for endpoint: " + (endpoint != null ? endpoint.getName() : "null"));

    // Clean up any stale state before retrying
    if (endpoint != null) {
      disconnect(endpoint);
    }

    // Wait a bit before retrying to avoid immediate retry conflicts
    // Let's try someone else after a short delay
    if (getState() == State.SEARCHING) {
      mCurrentStateView.postDelayed(new Runnable() {
        @Override
        public void run() {
          if (getState() == State.SEARCHING) {
            logD("Retrying discovery after connection failure");
            // Check permissions before retrying
            if (hasPermissions(MainActivity.this, getRequiredPermissions())) {
              startDiscovering();
            } else {
              logW("Missing permissions, cannot retry discovery");
              Toast.makeText(MainActivity.this, "Missing permissions. Please grant location permission.",
                  Toast.LENGTH_SHORT).show();
            }
          }
        }
      }, 2000); // Wait 2 seconds before retrying
    }
  }

  /**
   * The state has changed. I wonder what we'll be doing now.
   *
   * @param state The new state.
   */
  private void setState(State state) {
    if (mState == state) {
      logW("State set to " + state + " but already in that state");
      return;
    }

    logD("State set to " + state);
    State oldState = mState;
    mState = state;
    onStateChanged(oldState, state);
  }

  /** @return The current state. */
  private State getState() {
    return mState;
  }

  /**
   * State has changed.
   *
   * @param oldState The previous state we were in. Clean up anything related to this state.
   * @param newState The new state we're now in. Prepare the UI for this state.
   */
  private void onStateChanged(State oldState, State newState) {
    if (mCurrentAnimator != null && mCurrentAnimator.isRunning()) {
      mCurrentAnimator.cancel();
    }

    // Update Nearby Connections to the new state.
    switch (newState) {
      case SEARCHING:
        disconnectFromAllEndpoints();
        logD("SEARCHING state: Starting discovering and advertising");
        startDiscovering();
        // Add a small delay before starting advertising to ensure discovery starts
        // first
        mCurrentStateView.postDelayed(new Runnable() {
          @Override
          public void run() {
            if (getState() == State.SEARCHING) {
              logD("SEARCHING state: Now starting advertising");
              startAdvertising();
            }
          }
        }, 500);
        break;
      case CONNECTED:
        stopDiscovering();
        stopAdvertising();
        break;
      case UNKNOWN:
        stopAllEndpoints();
        break;
      default:
        // no-op
        break;
    }

    // Update the UI.
    switch (oldState) {
      case UNKNOWN:
        // Unknown is our initial state. Whatever state we move to,
        // we're transitioning forwards.
        transitionForward(oldState, newState);
        break;
      case SEARCHING:
        switch (newState) {
          case UNKNOWN:
            transitionBackward(oldState, newState);
            break;
          case CONNECTED:
            transitionForward(oldState, newState);
            break;
          default:
            // no-op
            break;
        }
        break;
      case CONNECTED:
        // Connected is our final state. Whatever new state we move to,
        // we're transitioning backwards.
        transitionBackward(oldState, newState);
        break;
    }
  }

  /** Transitions from the old state to the new state with an animation implying moving forward. */
  @UiThread
  private void transitionForward(State oldState, final State newState) {
    mPreviousStateView.setVisibility(View.VISIBLE);
    mCurrentStateView.setVisibility(View.VISIBLE);

    updateTextView(mPreviousStateView, oldState);
    updateTextView(mCurrentStateView, newState);

    if (ViewCompat.isLaidOut(mCurrentStateView)) {
      mCurrentAnimator = createAnimator(false /* reverse */);
      mCurrentAnimator.addListener(
          new AnimatorListener() {
            @Override
            public void onAnimationEnd(Animator animator) {
              updateTextView(mCurrentStateView, newState);
            }
          });
      mCurrentAnimator.start();
    }
  }

  /** Transitions from the old state to the new state with an animation implying moving backward. */
  @UiThread
  private void transitionBackward(State oldState, final State newState) {
    mPreviousStateView.setVisibility(View.VISIBLE);
    mCurrentStateView.setVisibility(View.VISIBLE);

    updateTextView(mCurrentStateView, oldState);
    updateTextView(mPreviousStateView, newState);

    if (ViewCompat.isLaidOut(mCurrentStateView)) {
      mCurrentAnimator = createAnimator(true /* reverse */);
      mCurrentAnimator.addListener(
          new AnimatorListener() {
            @Override
            public void onAnimationEnd(Animator animator) {
              updateTextView(mCurrentStateView, newState);
            }
          });
      mCurrentAnimator.start();
    }
  }

  @NonNull
  private Animator createAnimator(boolean reverse) {
    Animator animator;
    if (Build.VERSION.SDK_INT >= 21) {
      int cx = mCurrentStateView.getMeasuredWidth() / 2;
      int cy = mCurrentStateView.getMeasuredHeight() / 2;
      int initialRadius = 0;
      int finalRadius = Math.max(mCurrentStateView.getWidth(), mCurrentStateView.getHeight());
      if (reverse) {
        int temp = initialRadius;
        initialRadius = finalRadius;
        finalRadius = temp;
      }
      animator =
          ViewAnimationUtils.createCircularReveal(
              mCurrentStateView, cx, cy, initialRadius, finalRadius);
    } else {
      float initialAlpha = 0f;
      float finalAlpha = 1f;
      if (reverse) {
        float temp = initialAlpha;
        initialAlpha = finalAlpha;
        finalAlpha = temp;
      }
      mCurrentStateView.setAlpha(initialAlpha);
      animator = ObjectAnimator.ofFloat(mCurrentStateView, "alpha", finalAlpha);
    }
    animator.addListener(
        new AnimatorListener() {
          @Override
          public void onAnimationCancel(Animator animator) {
            mPreviousStateView.setVisibility(View.GONE);
            mCurrentStateView.setAlpha(1);
          }

          @Override
          public void onAnimationEnd(Animator animator) {
            mPreviousStateView.setVisibility(View.GONE);
            mCurrentStateView.setAlpha(1);
          }
        });
    animator.setDuration(ANIMATION_DURATION);
    return animator;
  }

  /** Updates the {@link TextView} with the correct color/text for the given {@link State}. */
  @UiThread
  private void updateTextView(TextView textView, State state) {
    switch (state) {
      case SEARCHING:
        textView.setBackgroundResource(R.color.state_searching);
        textView.setText(R.string.status_searching);
        break;
      case CONNECTED:
        textView.setBackgroundColor(mConnectedColor);
        textView.setText(R.string.status_connected);
        break;
      default:
        textView.setBackgroundResource(R.color.state_unknown);
        textView.setText(R.string.status_unknown);
        break;
    }
  }

  /** {@see ConnectionsActivity#onReceive(Endpoint, Payload)} */
  @Override
  protected void onReceive(Endpoint endpoint, Payload payload) {
    logD("Received payload from " + endpoint.getName() + ", type: " + payload.getType());

    if (payload.getType() == Payload.Type.STREAM) {
      if (mAudioPlayer != null) {
        mAudioPlayer.stop();
        mAudioPlayer = null;
      }

      AudioPlayer player =
          new AudioPlayer(payload.asStream().asInputStream()) {
            @WorkerThread
            @Override
            protected void onFinish() {
              runOnUiThread(
                  new Runnable() {
                    @UiThread
                    @Override
                    public void run() {
                      mAudioPlayer = null;
                    }
                  });
            }
          };
      mAudioPlayer = player;
      player.start();
    } else if (payload.getType() == Payload.Type.FILE) {
      logD("Received FILE payload, ID: " + payload.getId());
      mReceivedFilePayloads.put(payload.getId(), payload);
      Toast.makeText(this, "Receiving file from " + endpoint.getName() + "...", Toast.LENGTH_SHORT).show();
    } else if (payload.getType() == Payload.Type.BYTES) {
      logD("Received BYTES payload");
      byte[] bytes = payload.asBytes();
      Toast.makeText(this, "Received " + bytes.length + " bytes from " + endpoint.getName(), Toast.LENGTH_SHORT).show();
    }
  }

  @Override
  protected void onPayloadTransferUpdate(Endpoint endpoint, PayloadTransferUpdate update) {
    super.onPayloadTransferUpdate(endpoint, update);
    if (update.getStatus() == PayloadTransferUpdate.Status.SUCCESS) {
      Payload payload = mReceivedFilePayloads.get(update.getPayloadId());
      if (payload != null && payload.getType() == Payload.Type.FILE) {
        new Thread(new Runnable() {
          @Override
          @WorkerThread
          public void run() {
            try {
              com.google.android.gms.nearby.connection.Payload.File payloadFile = payload.asFile();
              String fileName = "received_image_" + System.currentTimeMillis() + ".jpg";
              String finalPath;
              String finalName = fileName;

              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.Downloads.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                  try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    ParcelFileDescriptor pfd = payloadFile.asParcelFileDescriptor();
                    if (pfd != null) {
                      try (InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                          os.write(buffer, 0, bytesRead);
                        }
                      }
                    } else {
                      throw new Exception("Failed to get ParcelFileDescriptor from payload");
                    }
                  }
                  values.clear();
                  values.put(MediaStore.Downloads.IS_PENDING, 0);
                  getContentResolver().update(uri, values, null, null);
                  finalPath = "Downloads/" + fileName;
                } else {
                  throw new Exception("Failed to create MediaStore entry");
                }
              } else {
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadsDir.exists()) {
                  downloadsDir.mkdirs();
                }
                File destFile = new File(downloadsDir, fileName);
                if (destFile.exists()) {
                  String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
                  String extension = fileName.substring(fileName.lastIndexOf('.'));
                  finalName = baseName + "_" + System.currentTimeMillis() + extension;
                  destFile = new File(downloadsDir, finalName);
                }
                ParcelFileDescriptor pfd = payloadFile.asParcelFileDescriptor();
                if (pfd != null) {
                  try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                      OutputStream out = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                      out.write(buffer, 0, bytesRead);
                    }
                  }
                } else {
                  throw new Exception("Failed to get ParcelFileDescriptor from payload");
                }
                finalPath = destFile.getAbsolutePath();
              }

              final String finalName1 = finalName;
              final String finalPath1 = finalPath;
              runOnUiThread(new Runnable() {
                @Override
                @UiThread
                public void run() {
                  Toast.makeText(MainActivity.this,
                      "File saved: " + finalName1 + "\nLocation: Downloads folder\nPath: " + finalPath1,
                      Toast.LENGTH_LONG).show();
                }
              });
              mReceivedFilePayloads.remove(update.getPayloadId());
            } catch (Exception e) {
              logE("Error processing received file", e);
              runOnUiThread(new Runnable() {
                @Override
                @UiThread
                public void run() {
                  Toast.makeText(MainActivity.this, "File transfer completed but error saving: " + e.getMessage(),
                      Toast.LENGTH_LONG).show();
                }
              });
              mReceivedFilePayloads.remove(update.getPayloadId());
            }
          }
        }).start();
      }
    } else if (update.getStatus() == PayloadTransferUpdate.Status.FAILURE) {
      Payload payload = mReceivedFilePayloads.get(update.getPayloadId());
      if (payload != null && payload.getType() == Payload.Type.FILE) {
        logE("File transfer failed", null);
        Toast.makeText(this, "File transfer failed", Toast.LENGTH_SHORT).show();
        mReceivedFilePayloads.remove(update.getPayloadId());
      }
    }
  }

  /** Stops all currently streaming audio tracks. */
  private void stopPlaying() {
    logV("stopPlaying()");
    if (mAudioPlayer != null) {
      mAudioPlayer.stop();
      mAudioPlayer = null;
    }
  }

  /** @return True if currently playing. */
  private boolean isPlaying() {
    return mAudioPlayer != null;
  }

  /** Starts recording sound from the microphone and streaming it to all connected devices. */
  private void startRecording() {
    logV("startRecording()");
    try {
      ParcelFileDescriptor[] payloadPipe = ParcelFileDescriptor.createPipe();

      // Send the first half of the payload (the read side) to Nearby Connections.
      send(Payload.fromStream(payloadPipe[0]));

      // Use the second half of the payload (the write side) in AudioRecorder.
      mRecorder = new AudioRecorder(payloadPipe[1]);
      mRecorder.start();
    } catch (IOException e) {
      logE("startRecording() failed", e);
    }
  }

  /** Stops streaming sound from the microphone. */
  private void stopRecording() {
    logV("stopRecording()");
    if (mRecorder != null) {
      mRecorder.stop();
      mRecorder = null;
    }
  }

  /** @return True if currently streaming from the microphone. */
  private boolean isRecording() {
    return mRecorder != null && mRecorder.isRecording();
  }

  /** {@see ConnectionsActivity#getRequiredPermissions()} */
  @Override
  protected String[] getRequiredPermissions() {
    return join(
        super.getRequiredPermissions(),
        Manifest.permission.RECORD_AUDIO);
  }

  /** Joins 2 arrays together. */
  private static String[] join(String[] a, String... b) {
    String[] join = new String[a.length + b.length];
    System.arraycopy(a, 0, join, 0, a.length);
    System.arraycopy(b, 0, join, a.length, b.length);
    return join;
  }

  /**
   * Queries the phone's contacts for their own profile, and returns their name. Used when
   * connecting to another device.
   */
  @Override
  protected String getName() {
    return mName;
  }

  /** {@see ConnectionsActivity#getServiceId()} */
  @Override
  public String getServiceId() {
    return SERVICE_ID;
  }

  /** {@see ConnectionsActivity#getStrategy()} */
  @Override
  public Strategy getStrategy() {
    return STRATEGY;
  }

  @Override
  protected void logV(String msg) {
    super.logV(msg);
    appendToLogs(toColor(msg, getResources().getColor(R.color.log_verbose)));
  }

  @Override
  protected void logD(String msg) {
    super.logD(msg);
    appendToLogs(toColor(msg, getResources().getColor(R.color.log_debug)));
  }

  @Override
  protected void logW(String msg) {
    super.logW(msg);
    appendToLogs(toColor(msg, getResources().getColor(R.color.log_warning)));
  }

  @Override
  protected void logW(String msg, Throwable e) {
    super.logW(msg, e);
    appendToLogs(toColor(msg, getResources().getColor(R.color.log_warning)));
  }

  @Override
  protected void logE(String msg, Throwable e) {
    super.logE(msg, e);
    appendToLogs(toColor(msg, getResources().getColor(R.color.log_error)));
  }

  private void appendToLogs(CharSequence msg) {
    mDebugLogView.append("\n");
    mDebugLogView.append(DateFormat.format("hh:mm", System.currentTimeMillis()) + ": ");
    mDebugLogView.append(msg);
  }

  private static CharSequence toColor(String msg, int color) {
    SpannableString spannable = new SpannableString(msg);
    spannable.setSpan(new ForegroundColorSpan(color), 0, msg.length(), 0);
    return spannable;
  }

  private static String generateRandomName() {
    String name = "";
    Random random = new Random();
    for (int i = 0; i < 5; i++) {
      name += random.nextInt(10);
    }
    return name;
  }

  /**
   * Provides an implementation of Animator.AnimatorListener so that we only have to override the
   * method(s) we're interested in.
   */
  private abstract static class AnimatorListener implements Animator.AnimatorListener {
    @Override
    public void onAnimationStart(Animator animator) {}

    @Override
    public void onAnimationEnd(Animator animator) {}

    @Override
    public void onAnimationCancel(Animator animator) {}

    @Override
    public void onAnimationRepeat(Animator animator) {}
  }

  /** States that the UI goes through. */
  public enum State {
    UNKNOWN,
    SEARCHING,
    CONNECTED
  }
}
