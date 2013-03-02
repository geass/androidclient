/*
 * Kontalk Android client
 * Copyright (C) 2011 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.xmpp.service;

import static org.kontalk.xmpp.ui.MessagingNotification.NOTIFICATION_ID_UPLOADING;
import static org.kontalk.xmpp.ui.MessagingNotification.NOTIFICATION_ID_UPLOAD_ERROR;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import org.kontalk.xmpp.R;
import org.kontalk.xmpp.authenticator.Authenticator;
import org.kontalk.xmpp.message.AbstractMessage;
import org.kontalk.xmpp.ui.ConversationList;
import org.kontalk.xmpp.upload.KontalkBoxUploadConnection;
import org.kontalk.xmpp.upload.UploadConnection;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.RemoteViews;


/**
 * Attachment upload service.
 * TODO implement multiple concurrent uploads
 * @author Daniele Ricci
 */
public class UploadService extends IntentService implements ProgressListener {
    private static final String TAG = UploadService.class.getSimpleName();
    /** A map to avoid duplicate uploads. */
    private static final Map<String, Long> queue = new LinkedHashMap<String, Long>();

    public static final String ACTION_UPLOAD = "org.kontalk.action.UPLOAD";
    public static final String ACTION_UPLOAD_ABORT = "org.kontalk.action.UPLOAD_ABORT";

    /** Message database ID. Use with ACTION_UPLOAD. */
    public static final String EXTRA_MESSAGE_ID = "org.kontalk.upload.MESSAGE_ID";
    /** URL to post to. Use with ACTION_UPLOAD. */
    public static final String EXTRA_POST_URL = "org.kontalk.upload.POST_URL";
    // Intent data is the local file Uri

    // data about the upload currently being processed
    private Notification mCurrentNotification;
    private long mTotalBytes;

    private long mMessageId;
    private UploadConnection mConn;
    private boolean mCanceled;

    public UploadService() {
        super(UploadService.class.getSimpleName());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (ACTION_UPLOAD_ABORT.equals(intent.getAction())) {
            String filename = intent.getData().toString();
            // TODO check for race conditions on queue
            Long msgId = queue.get(filename);
            if (msgId != null) {
                // interrupt worker if running
                if (msgId.longValue() == mMessageId) {
                    mConn.abort();
                    mCanceled = true;
                }
                // remove from queue - will never be processed
                else
                    queue.remove(filename);
            }
            return START_NOT_STICKY;
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        // check for unknown action
        if (!ACTION_UPLOAD.equals(intent.getAction())) return;

        // local file to upload
        Uri file = intent.getData();
        String filename = file.toString();
        // message id
        long msgId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0);
        // url to post to
        String url = intent.getStringExtra(EXTRA_POST_URL);

        // check if upload has already been queued
        if (queue.get(filename) != null) return;

        // notify user about download immediately
        startForeground(0);
        mCanceled = false;

        if (mConn == null) {
            String token = Authenticator.getDefaultAccountToken(this);
            mConn = new KontalkBoxUploadConnection(this, url, token);
        }

        try {
            mMessageId = msgId;
            queue.put(filename, mMessageId);

            // download content
            mConn.upload(file, "application/octet-stream" /* TODO */, null, this);

            // end operations
            completed();
        }
        catch (Exception e) {
            error(url, null, e);
        }
        finally {
            queue.remove(url);
            mMessageId = 0;
        }
    }

    public void startForeground(long totalBytes) {
        Log.d(TAG, "starting foreground progress notification");
        mTotalBytes = totalBytes;

        Intent ni = new Intent(getApplicationContext(), ConversationList.class);
        // FIXME this intent should actually open the ComposeMessage activity
        PendingIntent pi = PendingIntent.getActivity(getApplicationContext(),
                NOTIFICATION_ID_UPLOADING, ni, Intent.FLAG_ACTIVITY_NEW_TASK);

        mCurrentNotification = new Notification(R.drawable.icon_stat,
                getString(R.string.downloading_attachment), System.currentTimeMillis());
        mCurrentNotification.contentIntent = pi;
        mCurrentNotification.flags |= Notification.FLAG_ONGOING_EVENT;

        foregroundNotification(0);
        startForeground(NOTIFICATION_ID_UPLOADING, mCurrentNotification);
    }

    private void foregroundNotification(int progress) {
        mCurrentNotification.contentView = new RemoteViews(getApplicationContext().getPackageName(), R.layout.progress_notification);
        mCurrentNotification.contentView.setTextViewText(R.id.title, getString(R.string.downloading_attachment));
        mCurrentNotification.contentView.setTextViewText(R.id.progress_text, String.format("%d%%", progress));
        mCurrentNotification.contentView.setProgressBar(R.id.progress_bar, 100, progress, false);
    }

    public void stopForeground() {
        stopForeground(true);
        mCurrentNotification = null;
        mTotalBytes = 0;
    }

    public void completed() {
        stopForeground();

        // upload completed - no need for notification

        // TODO broadcast upload completed intent
    }

    public void error(String url, File destination, Throwable exc) {
        Log.e(TAG, "download error", exc);
        stopForeground();
        if (!mCanceled)
            errorNotification(getString(R.string.notify_ticker_download_error),
                getString(R.string.notify_text_download_error));
    }

    private void errorNotification(String ticker, String text) {
        // create intent for download error notification
        Intent i = new Intent(this, ConversationList.class);
        PendingIntent pi = PendingIntent.getActivity(getApplicationContext(),
                NOTIFICATION_ID_UPLOAD_ERROR, i, Intent.FLAG_ACTIVITY_NEW_TASK);

        // create notification
        Notification no = new Notification(R.drawable.icon_stat,
                ticker,
                System.currentTimeMillis());
        no.setLatestEventInfo(getApplicationContext(),
                getString(R.string.notify_title_download_error),
                text, pi);
        no.flags |= Notification.FLAG_AUTO_CANCEL;

        // notify!!
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID_UPLOAD_ERROR, no);
    }

    @Override
    public void progress(UploadConnection conn, long bytes) {
        if (mCurrentNotification != null) {
            int progress = (int)((100 * bytes) / mTotalBytes);
            foregroundNotification(progress);
            // send the updates to the notification manager
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIFICATION_ID_UPLOADING, mCurrentNotification);
        }

        Thread.yield();
    }

    public static boolean isQueued(String url) {
        return queue.containsKey(url);
    }
}