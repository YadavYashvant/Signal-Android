/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import org.signal.core.util.StreamUtil;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.util.Util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Records audio in WAV format using AudioRecord.
 */
public class WavRecorder implements Recorder {

  private static final String TAG = Log.tag(WavRecorder.class);

  private static final int SAMPLE_RATE = 44100;
  private static final int CHANNELS = 1;
  private static final int BITS_PER_SAMPLE = 16;
  private static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
  private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;

  private final int bufferSize;
  private final AudioRecord audioRecord;

  private boolean running = true;
  private boolean finished = false;

  @SuppressLint("MissingPermission")
  public WavRecorder() throws IOException {
    this.bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING);

    if (this.bufferSize == AudioRecord.ERROR || this.bufferSize == AudioRecord.ERROR_BAD_VALUE) {
      throw new IOException("Failed to calculate buffer size for AudioRecord");
    }

    try {
      this.audioRecord = new AudioRecord(
        MediaRecorder.AudioSource.MIC,
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_ENCODING,
        this.bufferSize
      );

      if (this.audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
        throw new IOException("AudioRecord failed to initialize");
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to create AudioRecord", e);
      throw new IOException(e);
    }

    try {
      audioRecord.startRecording();
    } catch (Exception e) {
      Log.w(TAG, "Failed to start recording", e);
      audioRecord.release();
      throw new IOException(e);
    }
  }

  @Override
  public void start(ParcelFileDescriptor fileDescriptor) {
    Log.i(TAG, "Recording voice note using WavRecorder.");
    start(new ParcelFileDescriptor.AutoCloseOutputStream(fileDescriptor));
  }

  @Override
  public synchronized void stop() {
    running = false;
    while (!finished) Util.wait(this, 0);
  }

  private void start(final OutputStream outputStream) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

        byte[] audioData = new byte[bufferSize];
        long totalAudioLen = 0;

        try {
          // Write WAV header with placeholder values
          writeWavHeader(outputStream, 0, SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE);

          // Record audio data
          while (running) {
            int bytesRead = audioRecord.read(audioData, 0, bufferSize);

            if (bytesRead > 0) {
              outputStream.write(audioData, 0, bytesRead);
              totalAudioLen += bytesRead;
            } else if (bytesRead < 0) {
              Log.w(TAG, "Error reading audio data: " + bytesRead);
              break;
            }
          }

          Log.i(TAG, "Recording finished. Total audio length: " + totalAudioLen + " bytes");

          // Note: We cannot update the header with actual size when using streams
          // The header will have placeholder values, but most players can handle this

        } catch (IOException e) {
          Log.w(TAG, "Error during recording", e);
        } finally {
          try {
            audioRecord.stop();
          } catch (IllegalStateException ise) {
            Log.w(TAG, "audioRecord stop failed.", ise);
          }

          try {
            audioRecord.release();
          } catch (Exception e) {
            Log.w(TAG, "audioRecord release failed.", e);
          }

          StreamUtil.close(outputStream);

          synchronized (WavRecorder.this) {
            finished = true;
            WavRecorder.this.notifyAll();
          }
        }
      }
    }).start();
  }

  /**
   * Writes WAV file header to the output stream.
   * WAV format specification:
   * - RIFF header
   * - fmt chunk (format information)
   * - data chunk (audio samples)
   */
  private void writeWavHeader(OutputStream out, long audioDataLength, int sampleRate,
                              int channels, int bitsPerSample) throws IOException {
    long totalDataLen = audioDataLength + 36; // 36 = size of header - 8 bytes for RIFF chunk descriptor
    long byteRate = (long) sampleRate * channels * bitsPerSample / 8;

    ByteBuffer header = ByteBuffer.allocate(44);
    header.order(ByteOrder.LITTLE_ENDIAN);

    // RIFF chunk descriptor
    header.put("RIFF".getBytes());
    header.putInt((int) totalDataLen); // File size - 8
    header.put("WAVE".getBytes());

    // fmt sub-chunk
    header.put("fmt ".getBytes());
    header.putInt(16); // Subchunk1Size (16 for PCM)
    header.putShort((short) 1); // AudioFormat (1 for PCM)
    header.putShort((short) channels); // NumChannels
    header.putInt(sampleRate); // SampleRate
    header.putInt((int) byteRate); // ByteRate
    header.putShort((short) (channels * bitsPerSample / 8)); // BlockAlign
    header.putShort((short) bitsPerSample); // BitsPerSample

    // data sub-chunk
    header.put("data".getBytes());
    header.putInt((int) audioDataLength); // Subchunk2Size

    out.write(header.array());
  }
}

