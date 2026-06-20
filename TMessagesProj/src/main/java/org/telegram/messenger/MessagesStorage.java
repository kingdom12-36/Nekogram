/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import static org.telegram.messenger.MessagesController.LOAD_AROUND_DATE;
import static org.telegram.messenger.MessagesController.LOAD_AROUND_MESSAGE;
import static org.telegram.messenger.MessagesController.LOAD_BACKWARD;
import static org.telegram.messenger.MessagesController.LOAD_FORWARD;
import static org.telegram.messenger.MessagesController.LOAD_FROM_UNREAD;

import android.appwidget.AppWidgetManager;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.annotation.UiThread;
import androidx.collection.LongSparseArray;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.support.LongSparseIntArray;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_bots;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Adapters.DialogsSearchAdapter;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.Reactions.ReactionsUtils;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.EditWidgetActivity;
import org.telegram.ui.Stories.StoriesController;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class MessagesStorage extends BaseController {

    private DispatchQueue storageQueue;
    private SQLiteDatabase database;
    private File cacheFile;
    private File walCacheFile;
    private File shmCacheFile;
    private final AtomicLong lastTaskId = new AtomicLong(System.currentTimeMillis());
    private final SparseArray<ArrayList<Runnable>> tasks = new SparseArray<>();

    private int lastDateValue = 0;
    private int lastPtsValue = 0;
    private int lastQtsValue = 0;
    private int lastSeqValue = 0;
    private int lastSecretVersion = 0;
    private byte[] secretPBytes = null;
    private int secretG = 0;

    private int lastSavedSeq = 0;
    private int lastSavedPts = 0;
    private int lastSavedDate = 0;
    private int lastSavedQts = 0;

    private final ArrayList<MessagesController.DialogFilter> dialogFilters = new ArrayList<>();
    private final SparseArray<MessagesController.DialogFilter> dialogFiltersMap = new SparseArray<>();
    private final LongSparseArray<Boolean> unknownDialogsIds = new LongSparseArray<>();
    private int mainUnreadCount;
    private int archiveUnreadCount;
    private volatile int pendingMainUnreadCount;
    private volatile int pendingArchiveUnreadCount;
    private boolean databaseCreated;

    private final CountDownLatch openSync = new CountDownLatch(1);

    private static volatile MessagesStorage[] Instance = new MessagesStorage[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];
    static {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            lockObjects[i] = new Object();
        }
    }

    public final static int LAST_DB_VERSION = 175;
    private boolean databaseMigrationInProgress;
    public boolean showClearDatabaseAlert;

    public static final int FORUM_TYPE_CHAT = 1;
    public static final int FORUM_TYPE_CHAT_TABS = 1 << 1;
    public static final int FORUM_TYPE_DIRECT = 1 << 2;
    public static final int FORUM_TYPE_BOT = 1 << 3;

    private final LongSparseIntArray dialogIsForumTyped = new LongSparseIntArray();


    public static MessagesStorage getInstance(int num) {
        MessagesStorage localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (lockObjects[num]) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new MessagesStorage(num);
                }
            }
        }
        return localInstance;
    }

    private void ensureOpened() {
        try {
            openSync.await();
        } catch (Throwable ignore) {

        }
    }

    public int getLastDateValue() {
        ensureOpened();
        return lastDateValue;
    }

    public void setLastDateValue(int value) {
        ensureOpened();
        lastDateValue = value;
    }

    public int getLastPtsValue() {
        ensureOpened();
        return lastPtsValue;
    }

    public int getMainUnreadCount() {
        return mainUnreadCount;
    }

    public int getArchiveUnreadCount() {
        return archiveUnreadCount;
    }

    public void setLastPtsValue(int value) {
        ensureOpened();
        lastPtsValue = value;
    }

    public int getLastQtsValue() {
        ensureOpened();
        return lastQtsValue;
    }

    public void setLastQtsValue(int value) {
        ensureOpened();
        lastQtsValue = value;
    }

    public int getLastSeqValue() {
        ensureOpened();
        return lastSeqValue;
    }

    public void setLastSeqValue(int value) {
        ensureOpened();
        lastSeqValue = value;
    }

    public int getLastSecretVersion() {
        ensureOpened();
        return lastSecretVersion;
    }

    public void setLastSecretVersion(int value) {
        ensureOpened();
        lastSecretVersion = value;
    }

    public byte[] getSecretPBytes() {
        ensureOpened();
        return secretPBytes;
    }

    public void setSecretPBytes(byte[] value) {
        ensureOpened();
        secretPBytes = value;
    }

    public int getSecretG() {
        ensureOpened();
        return secretG;
    }

    public void setSecretG(int value) {
        ensureOpened();
        secretG = value;
    }

    public MessagesStorage(int instance) {
        super(instance);
        storageQueue = new DispatchQueue("storageQueue_" + instance);
        storageQueue.setPriority(8);
        storageQueue.postRunnable(() -> openDatabase(1));
    }

    public SQLiteDatabase getDatabase() {
        return database;
    }

    public DispatchQueue getStorageQueue() {
        return storageQueue;
    }

    @UiThread
    public void bindTaskToGuid(Runnable task, int guid) {
        ArrayList<Runnable> arrayList = tasks.get(guid);
        if (arrayList == null) {
            arrayList = new ArrayList<>();
            tasks.put(guid, arrayList);
        }
        arrayList.add(task);
    }

    @UiThread
    public void cancelTasksForGuid(int guid) {
        ArrayList<Runnable> arrayList = tasks.get(guid);
        if (arrayList == null) {
            return;
        }
        for (int a = 0, N = arrayList.size(); a < N; a++) {
            storageQueue.cancelRunnable(arrayList.get(a));
        }
        tasks.remove(guid);
    }

    @UiThread
    public void completeTaskForGuid(Runnable runnable, int guid) {
        ArrayList<Runnable> arrayList = tasks.get(guid);
        if (arrayList == null) {
            return;
        }
        arrayList.remove(runnable);
        if (arrayList.isEmpty()) {
            tasks.remove(guid);
        }
    }

    public long getDatabaseSize() {
        long size = 0;
        if (cacheFile != null) {
            size += cacheFile.length();
        }
        if (shmCacheFile != null) {
            size += shmCacheFile.length();
        }
        /*if (walCacheFile != null) {
            size += walCacheFile.length();
        }*/
        return size;
    }

    public void openDatabase(int openTries) {
        if (!NativeLoader.loaded()) {
            int tryCount = 0;
            while (!NativeLoader.loaded()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                tryCount++;
                if (tryCount > 5) {
                    break;
                }
            }
        }
        File filesDir = ApplicationLoader.getFilesDirFixed();
        if (currentAccount != 0) {
            filesDir = new File(filesDir, "account" + currentAccount + "/");
            filesDir.mkdirs();
        }
        cacheFile = new File(filesDir, "cache4.db");
        walCacheFile = new File(filesDir, "cache4.db-wal");
        shmCacheFile = new File(filesDir, "cache4.db-shm");

        boolean createTable = false;

        databaseCreated = false;
        if (!cacheFile.exists()) {
            createTable = true;
        }
        try {
            database = new SQLiteDatabase(cacheFile.getPath());
            database.executeFast("PRAGMA secure_delete = ON").stepThis().dispose();
            database.executeFast("PRAGMA temp_store = MEMORY").stepThis().dispose();
            database.executeFast("PRAGMA journal_mode = WAL").stepThis().dispose();
            database.executeFast("PRAGMA journal_size_limit = 10485760").stepThis().dispose();

            if (createTable) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("create new database");
                }
                createTables(database);
            } else {
                int version = database.executeInt("PRAGMA user_version");
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("current db version = " + version);
                }
                if (version == 0) {
                    throw new Exception("malformed");
                }
                try {
                    SQLiteCursor cursor = database.queryFinalized("SELECT seq, pts, date, qts, lsv, sg, pbytes FROM params WHERE id = 1");
                    if (cursor.next()) {
                        lastSeqValue = cursor.intValue(0);
                        lastPtsValue = cursor.intValue(1);
                        lastDateValue = cursor.intValue(2);
                        lastQtsValue = cursor.intValue(3);
                        lastSecretVersion = cursor.intValue(4);
                        secretG = cursor.intValue(5);
                        if (cursor.isNull(6)) {
                            secretPBytes = null;
                        } else {
                            secretPBytes = cursor.byteArrayValue(6);
                            if (secretPBytes != null && secretPBytes.length == 1) {
                                secretPBytes = null;
                            }
                        }
                    }
                    cursor.dispose();
                } catch (Exception e) {
                    FileLog.e(e);
                    if (e.getMessage() != null && e.getMessage().contains("malformed")) {
                        throw new RuntimeException("malformed");
                    }
                    try {
                        database.executeFast("CREATE TABLE IF NOT EXISTS params(id INTEGER PRIMARY KEY, seq INTEGER, pts INTEGER, date INTEGER, qts INTEGER, lsv INTEGER, sg INTEGER, pbytes BLOB)").stepThis().dispose();
                        database.executeFast("INSERT INTO params VALUES(1, 0, 0, 0, 0, 0, 0, NULL)").stepThis().dispose();
                    } catch (Exception e2) {
                        FileLog.e(e2);
                    }
                }
                if (version < LAST_DB_VERSION) {
                    try {
                        updateDbToLastVersion(version);
                    } catch (Exception e) {
                        if (BuildVars.DEBUG_PRIVATE_VERSION) {
                            throw e;
                        }
                        FileLog.e(e);
                        throw new RuntimeException("malformed");
                    }
                }
            }
            databaseCreated = true;
        } catch (Exception e) {
            FileLog.e(e);
            if (openTries < 3 && e.getMessage() != null && e.getMessage().contains("malformed")) {
                if (openTries == 2) {
                    cleanupInternal(true);
                    clearLoadingDialogsOffsets();
                } else {
                    cleanupInternal(false);
                }
                openDatabase(openTries == 1 ? 2 : 3);
                return;
            }
        }

        AndroidUtilities.runOnUIThread(() -> {
            if (databaseMigrationInProgress) {
                databaseMigrationInProgress = false;
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.onDatabaseMigration, false);
            }
        });
        loadDialogFilters();
        loadUnreadMessages();
        loadPendingTasks();
        try {
            openSync.countDown();
        } catch (Throwable ignore) {

        }

        AndroidUtilities.runOnUIThread(() -> {
            //TODO add progress view and uncomment
            showClearDatabaseAlert = false;//getDatabaseSize() > 150 * 1024 * 1024;
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.onDatabaseOpened);
        });
    }

    private void clearLoadingDialogsOffsets() {
        for (int a = 0; a < 2; a++) {
            getUserConfig().setDialogsLoadOffset(a, 0, 0, 0, 0, 0, 0);
            getUserConfig().setTotalDialogsCount(a, 0);
        }
        getUserConfig().saveConfig(false);
    }

    private boolean recoverDatabase() {
        database.close();
        boolean restored = DatabaseMigrationHelper.recoverDatabase(cacheFile, walCacheFile, shmCacheFile, currentAccount);
        FileLog.e("Database restored = " + restored);
        if (restored) {
            try {
                database = new SQLiteDatabase(cacheFile.getPath());
                database.executeFast("PRAGMA secure_delete = ON").stepThis().dispose();
                database.executeFast("PRAGMA temp_store = MEMORY").stepThis().dispose();
                database.executeFast("PRAGMA journal_mode = WAL").stepThis().dispose();
                database.executeFast("PRAGMA journal_size_limit = 10485760").stepThis().dispose();
            } catch (SQLiteException e) {
                FileLog.e(new Exception(e));
                restored = false;
            }
        }
        if (!restored) {
            cleanupInternal(true);
            openDatabase(1);
            restored = databaseCreated;
            FileLog.e("Try create new database = " + restored);
        }
        if (restored) {
            reset();
        }
        return restored;
    }

    public final static String[] DATABASE_TABLES = new String[] {
            "messages_holes",
            "media_holes_v2",
            "scheduled_messages_v2",
            "quick_replies",
            "messages_v2",
            "download_queue",
            "user_contacts_v7",
            "user_phones_v7",
            "dialogs",
            "dialog_filter",
            "dialog_filter_ep",
            "dialog_filter_pin_v2",
            "randoms_v2",
            "enc_tasks_v4",
            "messages_seq",
            "params",
            "media_v4",
            "bot_keyboard",
            "bot_keyboard_topics",
            "chat_settings_v2",
            "user_settings",
            "chat_pinned_v2",
            "chat_pinned_count",
            "chat_hints",
            "botcache",
            "users_data",
            "users",
            "chats",
            "enc_chats",
            "channel_users_v2",
            "channel_admins_v3",
            "contacts",
            "dialog_photos",
            "dialog_settings",
            "web_recent_v3",
            "stickers_v2",
            "stickers_featured",
            "stickers_dice",
            "stickersets",
            "hashtag_recent_v2",
            "webpage_pending_v2",
            "sent_files_v2",
            "search_recent",
            "media_counts_v2",
            "keyvalue",
            "bot_info_v2",
            "pending_tasks",
            "requested_holes",
            "sharing_locations",
            "shortcut_widget",
            "emoji_keywords_v2",
            "emoji_keywords_info_v2",
            "wallpapers2",
            "unread_push_messages",
            "polls_v2",
            "reactions",
            "reaction_mentions",
            "downloading_documents",
            "animated_emoji",
            "attach_menu_bots",
            "premium_promo",
            "emoji_statuses",
            "messages_holes_topics",
            "messages_topics",
            "saved_dialogs",
            "media_topics",
            "media_holes_topics",
            "topics",
            "media_counts_topics",
            "reaction_mentions_topics",
            "emoji_groups",
            "poll_votes_mentions",
            "poll_votes_mentions_topics",
    };

    public static void createTables(SQLiteDatabase database) throws SQLiteException {
        database.executeFast("CREATE TABLE messages_holes(uid INTEGER, start INTEGER, end INTEGER, PRIMARY KEY(uid, start));").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS uid_end_messages_holes_4_dialogs ON messages_holes(uid, end);").stepThis().dispose();

        database.executeFast("CREATE TABLE media_holes_v2(uid INTEGER, type INTEGER, start INTEGER, end INTEGER, PRIMARY KEY(uid, type, start));").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS uid_end_media_holes_v2 ON media_holes_v2(uid, type, end);").stepThis().dispose();

        database.executeFast("CREATE TABLE scheduled_messages_v2(mid INTEGER, uid INTEGER, send_state INTEGER, date INTEGER, data BLOB, ttl INTEGER, replydata BLOB, reply_to_message_id INTEGER, PRIMARY KEY(mid, uid))").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS send_state_idx_scheduled_messages_v2 ON scheduled_messages_v2(mid, send_state, date);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS uid_date_idx_scheduled_messages_v2 ON scheduled_messages_v2(uid, date);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS reply_to_idx_scheduled_messages_v2 ON scheduled_messages_v2(mid, reply_to_message_id);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS idx_to_reply_scheduled_messages_v2 ON scheduled_messages_v2(reply_to_message_id, mid);").stepThis().dispose();

        database.executeFast("CREATE TABLE messages_v2(mid INTEGER, uid INTEGER, read_state INTEGER, send_state INTEGER, date INTEGER, data BLOB, out INTEGER, ttl INTEGER, media INTEGER, replydata BLOB, imp INTEGER, mention INTEGER, forwards INTEGER, replies_data BLOB, thread_reply_id INTEGER, is_channel INTEGER, reply_to_message_id INTEGER, custom_params BLOB, group_id INTEGER, reply_to_story_id INTEGER, PRIMARY KEY(mid, uid))").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS uid_mid_read_out_idx_messages_v2 ON messages_v2(uid, mid, read_state, out);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS uid_date_mid_idx_messages_v2 ON messages_v2(uid, date, mid);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS mid_out_idx_messages_v2 ON messages_v2(mid, out);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS task_idx_messages_v2 ON messages_v2(uid, out, read_state, ttl, date, send_state);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS send_state_idx_messages_v2 ON messages_v2(mid, send_state, date);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS uid_mention_idx_messages_v2 ON messages_v2(uid, mention, read_state);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS is_channel_idx_messages_v2 ON messages_v2(mid, is_channel);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS reply_to_idx_messages_v2 ON messages_v2(mid, reply_to_message_id);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS idx_to_reply_messages_v2 ON messages_v2(reply_to_message_id, mid);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS uid_mid_groupid_messages_v2 ON messages_v2(uid, mid, group_id);").stepThis().dispose();

        database.executeFast("CREATE TABLE saved_dialogs(did INTEGER, date INTEGER, last_mid INTEGER, pinned INTEGER, flags INTEGER, folder_id INTEGER, last_mid_group INTEGER, count INTEGER, forumChatId INTEGER, unread_count INTEGER, max_read_id INTEGER, read_outbox INTEGER, PRIMARY KEY (did, forumChatId))").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS date_idx_4_saved_dialogs ON saved_dialogs(date);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS last_mid_idx_4_saved_dialogs ON saved_dialogs(last_mid);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS folder_id_idx_4_saved_dialogs ON saved_dialogs(folder_id);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS flags_idx_4_saved_dialogs ON saved_dialogs(flags);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS forum_idx_dialogs ON saved_dialogs(forumChatId);").stepThis().dispose();

        database.executeFast("CREATE TABLE download_queue(uid INTEGER, type INTEGER, date INTEGER, data BLOB, parent TEXT, PRIMARY KEY (uid, type));").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS type_date_idx_download_queue ON download_queue(type, date);").stepThis().dispose();

        database.executeFast("CREATE TABLE user_contacts_v7(key TEXT PRIMARY KEY, uid INTEGER, fname TEXT, sname TEXT, imported INTEGER)").stepThis().dispose();
        database.executeFast("CREATE TABLE user_phones_v7(key TEXT, phone TEXT, sphone TEXT, deleted INTEGER, PRIMARY KEY (key, phone))").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS sphone_deleted_idx_user_phones ON user_phones_v7(sphone, deleted);").stepThis().dispose();

        database.executeFast("CREATE TABLE dialogs(did INTEGER PRIMARY KEY, date INTEGER, unread_count INTEGER, last_mid INTEGER, inbox_max INTEGER, outbox_max INTEGER, last_mid_i INTEGER, unread_count_i INTEGER, pts INTEGER, date_i INTEGER, pinned INTEGER, flags INTEGER, folder_id INTEGER, data BLOB, unread_reactions INTEGER, last_mid_group INTEGER, ttl_period INTEGER, unread_poll_votes INTEGER)").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS date_idx_4_dialogs ON dialogs(date);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS last_mid_idx_4_dialogs ON dialogs(last_mid);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS unread_count_idx_dialogs ON dialogs(unread_count);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS last_mid_i_idx_dialogs ON dialogs(last_mid_i);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS unread_count_i_idx_dialogs ON dialogs(unread_count_i);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS folder_id_idx_4_dialogs ON dialogs(folder_id);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS flags_idx_4_dialogs ON dialogs(flags);").stepThis().dispose();

        database.executeFast("CREATE TABLE dialog_filter(id INTEGER PRIMARY KEY, ord INTEGER, unread_count INTEGER, flags INTEGER, title TEXT, color INTEGER DEFAULT -1, emoticon TEXT, entities BLOB, noanimate INTEGER)").stepThis().dispose();
        database.executeFast("CREATE TABLE dialog_filter_ep(id INTEGER, peer INTEGER, PRIMARY KEY (id, peer))").stepThis().dispose();
        database.executeFast("CREATE TABLE dialog_filter_pin_v2(id INTEGER, peer INTEGER, pin INTEGER, PRIMARY KEY (id, peer))").stepThis().dispose();

        database.executeFast("CREATE TABLE randoms_v2(random_id INTEGER, mid INTEGER, uid INTEGER, PRIMARY KEY (random_id, mid, uid))").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS mid_idx_randoms_v2 ON randoms_v2(mid, uid);").stepThis().dispose();

        database.executeFast("CREATE TABLE enc_tasks_v4(mid INTEGER, uid INTEGER, date INTEGER, media INTEGER, PRIMARY KEY(mid, uid, media))").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS date_idx_enc_tasks_v4 ON enc_tasks_v4(date);").stepThis().dispose();

        database.executeFast("CREATE TABLE messages_seq(mid INTEGER PRIMARY KEY, seq_in INTEGER, seq_out INTEGER);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS seq_idx_messages_seq ON messages_seq(seq_in, seq_out);").stepThis().dispose();

        database.executeFast("CREATE TABLE params(id INTEGER PRIMARY KEY, seq INTEGER, pts INTEGER, date INTEGER, qts INTEGER, lsv INTEGER, sg INTEGER, pbytes BLOB)").stepThis().dispose();
        database.executeFast("INSERT INTO params VALUES(1, 0, 0, 0, 0, 0, 0, NULL)").stepThis().dispose();

        database.executeFast("CREATE TABLE media_v4(mid INTEGER, uid INTEGER, date INTEGER, type INTEGER, data BLOB, PRIMARY KEY(mid, uid, type))").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS uid_mid_type_date_idx_media_v4 ON media_v4(uid, mid, type, date);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS uid_type_date_mid_idx_media_v4 ON media_v4(uid, type, date DESC, mid DESC);").stepThis().dispose();

        database.executeFast("CREATE TABLE bot_keyboard(uid INTEGER PRIMARY KEY, mid INTEGER, info BLOB)").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS bot_keyboard_idx_mid_v2 ON bot_keyboard(mid, uid);").stepThis().dispose();

        database.executeFast("CREATE TABLE bot_keyboard_topics(uid INTEGER, tid INTEGER, mid INTEGER, info BLOB, PRIMARY KEY(uid, tid))").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS bot_keyboard_topics_idx_mid_v2 ON bot_keyboard_topics(mid, uid, tid);").stepThis().dispose();

        database.executeFast("CREATE TABLE chat_settings_v2(uid INTEGER PRIMARY KEY, info BLOB, pinned INTEGER, online INTEGER, inviter INTEGER, links INTEGER, participants_count INTEGER)").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS chat_settings_pinned_idx ON chat_settings_v2(uid, pinned) WHERE pinned != 0;").stepThis().dispose();

        database.executeFast("CREATE TABLE user_settings(uid INTEGER PRIMARY KEY, info BLOB, pinned INTEGER)").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS user_settings_pinned_idx ON user_settings(uid, pinned) WHERE pinned != 0;").stepThis().dispose();

        database.executeFast("CREATE TABLE chat_pinned_v2(uid INTEGER, mid INTEGER, data BLOB, PRIMARY KEY (uid, mid));").stepThis().dispose();
        database.executeFast("CREATE TABLE chat_pinned_count(uid INTEGER PRIMARY KEY, count INTEGER, end INTEGER);").stepThis().dispose();

        database.executeFast("CREATE TABLE chat_hints(did INTEGER, type INTEGER, rating REAL, date INTEGER, PRIMARY KEY(did, type))").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS chat_hints_rating_idx ON chat_hints(rating);").stepThis().dispose();

        database.executeFast("CREATE TABLE botcache(id TEXT PRIMARY KEY, date INTEGER, data BLOB)").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS botcache_date_idx ON botcache(date);").stepThis().dispose();

        database.executeFast("CREATE TABLE users_data(uid INTEGER PRIMARY KEY, about TEXT)").stepThis().dispose();
        database.executeFast("CREATE TABLE users(uid INTEGER PRIMARY KEY, name TEXT, status INTEGER, data BLOB)").stepThis().dispose();
        database.executeFast("CREATE TABLE chats(uid INTEGER PRIMARY KEY, name TEXT, data BLOB)").stepThis().dispose();
        database.executeFast("CREATE TABLE enc_chats(uid INTEGER PRIMARY KEY, user INTEGER, name TEXT, data BLOB, g BLOB, authkey BLOB, ttl INTEGER, layer INTEGER, seq_in INTEGER, seq_out INTEGER, use_count INTEGER, exchange_id INTEGER, key_date INTEGER, fprint INTEGER, fauthkey BLOB, khash BLOB, in_seq_no INTEGER, admin_id INTEGER, mtproto_seq INTEGER)").stepThis().dispose();
        database.executeFast("CREATE TABLE channel_users_v2(did INTEGER, uid INTEGER, date INTEGER, data BLOB, PRIMARY KEY(did, uid))").stepThis().dispose();
        database.executeFast("CREATE TABLE channel_admins_v3(did INTEGER, uid INTEGER, data BLOB, PRIMARY KEY(did, uid))").stepThis().dispose();
        database.executeFast("CREATE TABLE contacts(uid INTEGER PRIMARY KEY, mutual INTEGER)").stepThis().dispose();
        database.executeFast("CREATE TABLE dialog_photos(uid INTEGER, id INTEGER, num INTEGER, data BLOB, PRIMARY KEY (uid, id))").stepThis().dispose();
        database.executeFast("CREATE TABLE dialog_photos_count(uid INTEGER PRIMARY KEY, count INTEGER)").stepThis().dispose();
        database.executeFast("CREATE TABLE dialog_settings(did INTEGER PRIMARY KEY, flags INTEGER);").stepThis().dispose();
        database.executeFast("CREATE TABLE web_recent_v3(id TEXT, type INTEGER, image_url TEXT, thumb_url TEXT, local_url TEXT, width INTEGER, height INTEGER, size INTEGER, date INTEGER, document BLOB, PRIMARY KEY (id, type));").stepThis().dispose();
        database.executeFast("CREATE TABLE stickers_v2(id INTEGER PRIMARY KEY, data BLOB, date INTEGER, hash INTEGER);").stepThis().dispose();
        database.executeFast("CREATE TABLE stickers_featured(id INTEGER PRIMARY KEY, data BLOB, unread BLOB, date INTEGER, hash INTEGER, premium INTEGER, emoji INTEGER);").stepThis().dispose();
        database.executeFast("CREATE TABLE stickers_dice(emoji TEXT PRIMARY KEY, data BLOB, date INTEGER);").stepThis().dispose();
        database.executeFast("CREATE TABLE hashtag_recent_v2(id TEXT PRIMARY KEY, date INTEGER);").stepThis().dispose();
        database.executeFast("CREATE TABLE webpage_pending_v2(id INTEGER, mid INTEGER, uid INTEGER, PRIMARY KEY (id, mid, uid));").stepThis().dispose();
        database.executeFast("CREATE TABLE sent_files_v2(uid TEXT, type INTEGER, data BLOB, parent TEXT, PRIMARY KEY (uid, type))").stepThis().dispose();
        database.executeFast("CREATE TABLE search_recent(did INTEGER PRIMARY KEY, date INTEGER);").stepThis().dispose();
        database.executeFast("CREATE TABLE media_counts_v2(uid INTEGER, type INTEGER, count INTEGER, old INTEGER, PRIMARY KEY(uid, type))").stepThis().dispose();
        database.executeFast("CREATE TABLE keyvalue(id TEXT PRIMARY KEY, value TEXT)").stepThis().dispose();
        database.executeFast("CREATE TABLE bot_info_v2(uid INTEGER, dialogId INTEGER, info BLOB, PRIMARY KEY(uid, dialogId))").stepThis().dispose();
        database.executeFast("CREATE TABLE pending_tasks(id INTEGER PRIMARY KEY, data BLOB);").stepThis().dispose();
        database.executeFast("CREATE TABLE requested_holes(uid INTEGER, seq_out_start INTEGER, seq_out_end INTEGER, PRIMARY KEY (uid, seq_out_start, seq_out_end));").stepThis().dispose();
        database.executeFast("CREATE TABLE sharing_locations(uid INTEGER PRIMARY KEY, mid INTEGER, date INTEGER, period INTEGER, message BLOB, proximity INTEGER);").stepThis().dispose();

        database.executeFast("CREATE TABLE stickersets2(id INTEGER PRIMATE KEY, data BLOB, hash INTEGER, date INTEGER, short_name TEXT);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS stickersets2_id_index ON stickersets2(id);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS stickersets2_id_short_name ON stickersets2(id, short_name);").stepThis().dispose();

        database.executeFast("CREATE INDEX IF NOT EXISTS stickers_featured_emoji_index ON stickers_featured(emoji);").stepThis().dispose();

        database.executeFast("CREATE TABLE shortcut_widget(id INTEGER, did INTEGER, ord INTEGER, PRIMARY KEY (id, did));").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS shortcut_widget_did ON shortcut_widget(did);").stepThis().dispose();

        database.executeFast("CREATE TABLE emoji_keywords_v2(lang TEXT, keyword TEXT, emoji TEXT, PRIMARY KEY(lang, keyword, emoji));").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS emoji_keywords_v2_keyword ON emoji_keywords_v2(keyword);").stepThis().dispose();
        database.executeFast("CREATE TABLE emoji_keywords_info_v2(lang TEXT PRIMARY KEY, alias TEXT, version INTEGER, date INTEGER);").stepThis().dispose();

        database.executeFast("CREATE TABLE wallpapers2(uid INTEGER PRIMARY KEY, data BLOB, num INTEGER)").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS wallpapers_num ON wallpapers2(num);").stepThis().dispose();

        database.executeFast("CREATE TABLE unread_push_messages(uid INTEGER, mid INTEGER, random INTEGER, date INTEGER, data BLOB, fm TEXT, name TEXT, uname TEXT, flags INTEGER, topicId INTEGER, is_reaction INTEGER, PRIMARY KEY(uid, mid))").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS unread_push_messages_idx_date ON unread_push_messages(date);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS unread_push_messages_idx_random ON unread_push_messages(random);").stepThis().dispose();

        database.executeFast("CREATE TABLE polls_v2(mid INTEGER, uid INTEGER, id INTEGER, PRIMARY KEY (mid, uid));").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS polls_id_v2 ON polls_v2(id);").stepThis().dispose();

        database.executeFast("CREATE TABLE reactions(data BLOB, hash INTEGER, date INTEGER);").stepThis().dispose();
        database.executeFast("CREATE TABLE reaction_mentions(message_id INTEGER, state INTEGER, dialog_id INTEGER, PRIMARY KEY(message_id, dialog_id))").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS reaction_mentions_did ON reaction_mentions(dialog_id);").stepThis().dispose();

        database.executeFast("CREATE TABLE downloading_documents(data BLOB, hash INTEGER, id INTEGER, state INTEGER, date INTEGER, PRIMARY KEY(hash, id));").stepThis().dispose();
        database.executeFast("CREATE TABLE animated_emoji(document_id INTEGER PRIMARY KEY, data BLOB);").stepThis().dispose();

        database.executeFast("CREATE TABLE attach_menu_bots(data BLOB, hash INTEGER, date INTEGER);").stepThis().dispose();

        database.executeFast("CREATE TABLE premium_promo(data BLOB, date INTEGER);").stepThis().dispose();
        database.executeFast("CREATE TABLE emoji_statuses(data BLOB, type INTEGER);").stepThis().dispose();

        database.executeFast("CREATE TABLE messages_holes_topics(uid INTEGER, topic_id INTEGER, start INTEGER, end INTEGER, PRIMARY KEY(uid, topic_id, start));").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS uid_end_messages_holes_4_topics ON messages_holes_topics(uid, topic_id, end);").stepThis().dispose();

        database.executeFast("CREATE TABLE messages_topics(mid INTEGER, uid INTEGER, topic_id INTEGER, read_state INTEGER, send_state INTEGER, date INTEGER, data BLOB, out INTEGER, ttl INTEGER, media INTEGER, replydata BLOB, imp INTEGER, mention INTEGER, forwards INTEGER, replies_data BLOB, thread_reply_id INTEGER, is_channel INTEGER, reply_to_message_id INTEGER, custom_params BLOB, reply_to_story_id INTEGER, PRIMARY KEY(mid, topic_id, uid))").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS uid_date_mid_idx_messages_topics ON messages_topics(uid, date, mid);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS mid_out_idx_messages_topics ON messages_topics(mid, out);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS task_idx_messages_topics ON messages_topics(uid, out, read_state, ttl, date, send_state);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS send_state_idx_messages_topics ON messages_topics(mid, send_state, date);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS is_channel_idx_messages_topics ON messages_topics(mid, is_channel);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS reply_to_idx_messages_topics ON messages_topics(mid, reply_to_message_id);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS idx_to_reply_messages_topics ON messages_topics(reply_to_message_id, mid);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS mid_uid_messages_topics ON messages_topics(mid, uid);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS uid_mid_read_out_idx_messages_topics ON messages_topics(uid, topic_id, mid, read_state, out);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS uid_mention_idx_messages_topics ON messages_topics(uid, topic_id, mention, read_state);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS uid_topic_id_messages_topics ON messages_topics(uid, topic_id);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS uid_topic_id_date_mid_messages_topics ON messages_topics(uid, topic_id, date, mid);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS uid_topic_id_mid_messages_topics ON messages_topics(uid, topic_id, mid);").stepThis().dispose();


        database.executeFast("CREATE TABLE media_topics(mid INTEGER, uid INTEGER, topic_id INTEGER, date INTEGER, type INTEGER, data BLOB, PRIMARY KEY(mid, uid, topic_id, type))").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS uid_mid_type_date_idx_media_topics ON media_topics(uid, topic_id, mid, type, date);").stepThis().dispose();

        database.executeFast("CREATE TABLE media_holes_topics(uid INTEGER, topic_id INTEGER, type INTEGER, start INTEGER, end INTEGER, PRIMARY KEY(uid, topic_id, type, start));").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS uid_end_media_holes_topics ON media_holes_topics(uid, topic_id, type, end);").stepThis().dispose();

        database.executeFast("CREATE TABLE topics(did INTEGER, topic_id INTEGER, data BLOB, top_message INTEGER, topic_message BLOB, unread_count INTEGER, max_read_id INTEGER, unread_mentions INTEGER, unread_reactions INTEGER, read_outbox INTEGER, pinned INTEGER, total_messages_count INTEGER, hidden INTEGER, edit_date INTEGER, nopaid_messages_exception INTEGER, unread_poll_votes INTEGER, PRIMARY KEY(did, topic_id));").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS did_top_message_topics ON topics(did, top_message);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS did_topics ON topics(did);").stepThis().dispose();

        database.executeFast("CREATE TABLE media_counts_topics(uid INTEGER, topic_id INTEGER, type INTEGER, count INTEGER, old INTEGER, PRIMARY KEY(uid, topic_id, type))").stepThis().dispose();

        database.executeFast("CREATE TABLE reaction_mentions_topics(message_id INTEGER, state INTEGER, dialog_id INTEGER, topic_id INTEGER, PRIMARY KEY(message_id, dialog_id, topic_id))").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS reaction_mentions_topics_did ON reaction_mentions_topics(dialog_id, topic_id);").stepThis().dispose();

        database.executeFast("CREATE TABLE emoji_groups(type INTEGER PRIMARY KEY, data BLOB)").stepThis().dispose();
        database.executeFast("CREATE TABLE app_config(data BLOB)").stepThis().dispose();
        database.executeFast("CREATE TABLE web_browser_settings(data BLOB)").stepThis().dispose();
        database.executeFast("CREATE TABLE effects(data BLOB)").stepThis().dispose();

        database.executeFast("CREATE TABLE stories (dialog_id INTEGER, story_id INTEGER, data BLOB, custom_params BLOB, PRIMARY KEY (dialog_id, story_id));").stepThis().dispose();
        database.executeFast("CREATE TABLE stories_counter (dialog_id INTEGER PRIMARY KEY, count INTEGER, max_read INTEGER);").stepThis().dispose();

        database.executeFast("CREATE TABLE profile_stories (dialog_id INTEGER, story_id INTEGER, data BLOB, type INTEGER, seen INTEGER, pin INTEGER, PRIMARY KEY(dialog_id, story_id, type));").stepThis().dispose();
        database.executeFast("CREATE TABLE profile_stories_albums (dialog_id INTEGER, album_id INTEGER, order_index INTEGER, data BLOB, PRIMARY KEY(dialog_id, album_id));").stepThis().dispose();
        database.executeFast("CREATE TABLE profile_stories_albums_links (dialog_id INTEGER, album_id INTEGER, story_id INTEGER, order_index INTEGER, PRIMARY KEY (dialog_id, album_id, story_id));").stepThis().dispose();

        database.executeFast("CREATE TABLE story_drafts (id INTEGER PRIMARY KEY, date INTEGER, data BLOB, type INTEGER);").stepThis().dispose();

        database.executeFast("CREATE TABLE story_pushes (uid INTEGER, sid INTEGER, date INTEGER, localName TEXT, flags INTEGER, expire_date INTEGER, live INTEGER, PRIMARY KEY(uid, sid));").stepThis().dispose();

        database.executeFast("CREATE TABLE unconfirmed_auth (data BLOB);").stepThis().dispose();

        database.executeFast("CREATE TABLE saved_reaction_tags (topic_id INTEGER PRIMARY KEY, data BLOB);").stepThis().dispose();

        database.executeFast("CREATE TABLE tag_message_id(mid INTEGER, topic_id INTEGER, tag INTEGER, text TEXT);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS tag_idx_tag_message_id ON tag_message_id(tag);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS tag_text_idx_tag_message_id ON tag_message_id(tag, text COLLATE NOCASE);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS tag_topic_idx_tag_message_id ON tag_message_id(topic_id, tag);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS tag_topic_text_idx_tag_message_id ON tag_message_id(topic_id, tag, text COLLATE NOCASE);").stepThis().dispose();

        database.executeFast("CREATE TABLE business_replies(topic_id INTEGER PRIMARY KEY, name TEXT, order_value INTEGER, count INTEGER);").stepThis().dispose();
        database.executeFast("CREATE TABLE quick_replies_messages(mid INTEGER, topic_id INTEGER, send_state INTEGER, date INTEGER, data BLOB, ttl INTEGER, replydata BLOB, reply_to_message_id INTEGER, PRIMARY KEY(mid, topic_id))").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS send_state_idx_quick_replies_messages ON quick_replies_messages(mid, send_state, date);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS topic_date_idx_quick_replies_messages ON quick_replies_messages(topic_id, date);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS reply_to_idx_quick_replies_messages ON quick_replies_messages(mid, reply_to_message_id);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS idx_to_reply_quick_replies_messages ON quick_replies_messages(reply_to_message_id, mid);").stepThis().dispose();

        database.executeFast("CREATE TABLE business_links(data BLOB, order_value INTEGER);").stepThis().dispose();
        database.executeFast("CREATE TABLE fact_checks(hash INTEGER PRIMARY KEY, data BLOB, expires INTEGER);").stepThis().dispose();
        database.executeFast("CREATE TABLE popular_bots(uid INTEGER PRIMARY KEY, time INTEGER, offset TEXT, pos INTEGER);").stepThis().dispose();

        database.executeFast("CREATE TABLE star_gifts2(id INTEGER PRIMARY KEY, data BLOB, hash INTEGER, time INTEGER, pos INTEGER);").stepThis().dispose();

        database.executeFast("CREATE TABLE gift_themes (slug TEXT PRIMARY KEY, data BLOB);").stepThis().dispose();

        database.executeFast("CREATE TABLE poll_votes_mentions(message_id INTEGER, state INTEGER, dialog_id INTEGER, PRIMARY KEY(message_id, dialog_id))").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS poll_votes_mentions_did ON poll_votes_mentions(dialog_id);").stepThis().dispose();
        database.executeFast("CREATE TABLE poll_votes_mentions_topics(message_id INTEGER, state INTEGER, dialog_id INTEGER, topic_id INTEGER, PRIMARY KEY(message_id, dialog_id, topic_id))").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS poll_votes_mentions_topics_did ON poll_votes_mentions_topics(dialog_id, topic_id);").stepThis().dispose();

        database.executeFast("PRAGMA user_version = " + MessagesStorage.LAST_DB_VERSION).stepThis().dispose();

    }

    public boolean isDatabaseMigrationInProgress() {
        return databaseMigrationInProgress;
    }

    private void updateDbToLastVersion(int currentVersion) throws Exception {
        AndroidUtilities.runOnUIThread(() -> {
            databaseMigrationInProgress = true;
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.onDatabaseMigration, true);
        });

        int version = currentVersion;
        FileLog.d("MessagesStorage start db migration from " + version + " to " + LAST_DB_VERSION);
        version = DatabaseMigrationHelper.migrate(MessagesStorage.this, version);

        FileLog.d("MessagesStorage db migration finished to varsion " + version);
        AndroidUtilities.runOnUIThread(() -> {
            databaseMigrationInProgress = false;
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.onDatabaseMigration, false);
        });
    }

    private void cleanupInternal(boolean deleteFiles) {
        if (deleteFiles) {
            reset();
        } else {
            clearDatabaseValues();
        }
        if (database != null) {
            database.close();
            database = null;
        }
        if (deleteFiles) {
            if (cacheFile != null) {
                cacheFile.delete();
                cacheFile = null;
            }
            if (walCacheFile != null) {
                walCacheFile.delete();
                walCacheFile = null;
            }
            if (shmCacheFile != null) {
                shmCacheFile.delete();
                shmCacheFile = null;
            }

        }
    }

    public void clearDatabaseValues() {
        lastDateValue = 0;
        lastSeqValue = 0;
        lastPtsValue = 0;
        lastQtsValue = 0;
        lastSecretVersion = 0;
        mainUnreadCount = 0;
        archiveUnreadCount = 0;
        pendingMainUnreadCount = 0;
        pendingArchiveUnreadCount = 0;
        dialogFilters.clear();
        dialogFiltersMap.clear();
        unknownDialogsIds.clear();

        lastSavedSeq = 0;
        lastSavedPts = 0;
        lastSavedDate = 0;
        lastSavedQts = 0;

        secretPBytes = null;
        secretG = 0;
    }

    public void cleanup(boolean isLogin) {
        storageQueue.postRunnable(() -> {
            cleanupInternal(true);
            openDatabase(1);
            if (isLogin) {
                Utilities.stageQueue.postRunnable(() -> getMessagesController().getDifference());
            }
        });
    }

    public void saveSecretParams(int lsv, int sg, byte[] pbytes) {
        storageQueue.postRunnable(() -> {
            try {
                SQLitePreparedStatement state = database.executeFast("UPDATE params SET lsv = ?, sg = ?, pbytes = ? WHERE id = 1");
                state.bindInteger(1, lsv);
                state.bindInteger(2, sg);
                NativeByteBuffer data = new NativeByteBuffer(pbytes != null ? pbytes.length : 1);
                if (pbytes != null) {
                    data.writeBytes(pbytes);
                }
                state.bindByteBuffer(3, data);
                state.step();
                state.dispose();
                data.reuse();
            } catch (Exception e) {
                checkSQLException(e);
            }
        });
    }

    boolean tryRecover;

    public void checkSQLException(Throwable e) {
        checkSQLException(e, true);
    }

    private void checkSQLException(Throwable e, boolean logToAppCenter) {
        if (e instanceof SQLiteException && e.getMessage() != null && e.getMessage().contains("is malformed") && !tryRecover) {
            tryRecover = true;
            FileLog.e("disk image malformed detected, try recover");
            if (recoverDatabase()) {
                tryRecover = false;
                clearLoadingDialogsOffsets();
                AndroidUtilities.runOnUIThread(() -> {
                   getNotificationCenter().postNotificationName(NotificationCenter.onDatabaseReset);
                });
                FileLog.e(new Exception("database restored!!"));
            } else {
                FileLog.e(new Exception(e), logToAppCenter);
            }
        } else {
            FileLog.e(e, logToAppCenter);
        }
    }

    public void fixNotificationSettings() {
        storageQueue.postRunnable(() -> {
            try {
                LongSparseArray<Long> ids = new LongSparseArray<>();
                SharedPreferences preferences = MessagesController.getNotificationsSettings(currentAccount);
                Map<String, ?> values = preferences.getAll();
                for (Map.Entry<String, ?> entry : values.entrySet()) {
                    String key = entry.getKey();
                    if (key.startsWith("notify2_")) {
                        Integer value = (Integer) entry.getValue();
                        if (value == 2 || value == 3) {
                            key = key.replace("notify2_", "");
                            long flags;
                            if (value == 2) {
                                flags = 1;
                            } else {
                                Integer time = (Integer) values.get("notifyuntil_" + key);
                                if (time != null) {
                                    flags = ((long) time << 32) | 1;
                                } else {
                                    flags = 1;
                                }
                            }
                            try {
                                ids.put(Long.parseLong(key), flags);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
                try {
                    database.beginTransaction();
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO dialog_settings VALUES(?, ?)");
                    for (int a = 0; a < ids.size(); a++) {
                        state.requery();
                        state.bindLong(1, ids.keyAt(a));
                        state.bindLong(2, ids.valueAt(a));
                        state.step();
                    }
                    state.dispose();
                    database.commitTransaction();
                } catch (Exception e) {
                    checkSQLException(e);
                }
            } catch (Throwable e) {
                checkSQLException(e);
            }
        });
    }

    public long createPendingTask(NativeByteBuffer data) {
        if (data == null) {
            return 0;
        }
        long id = lastTaskId.getAndAdd(1);
        storageQueue.postRunnable(() -> {
            try {
                SQLitePreparedStatement state = database.executeFast("REPLACE INTO pending_tasks VALUES(?, ?)");
                state.bindLong(1, id);
                state.bindByteBuffer(2, data);
                state.step();
                state.dispose();
            } catch (Exception e) {
                checkSQLException(e);
            } finally {
                data.reuse();
            }
        });
        return id;
    }

    public void removePendingTask(long id) {
        storageQueue.postRunnable(() -> {
            try {
                database.executeFast("DELETE FROM pending_tasks WHERE id = " + id).stepThis().dispose();
            } catch (Exception e) {
                checkSQLException(e);
            }
        });
    }

    private void loadPendingTasks() {
        storageQueue.postRunnable(() -> {
            try {
                SQLiteCursor cursor = database.queryFinalized("SELECT id, data FROM pending_tasks WHERE 1");
                while (cursor.next()) {
                    long taskId = cursor.longValue(0);
                    NativeByteBuffer data = cursor.byteBufferValue(1);
                    if (data != null) {
                        int type = data.readInt32(false);
                        switch (type) {
                            case 0: {
                                TLRPC.Chat chat = TLRPC.Chat.TLdeserialize(data, data.readInt32(false), false);
                                if (chat != null) {
                                    Utilities.stageQueue.postRunnable(() -> getMessagesController().loadUnknownChannel(chat, taskId));
                                }
                                break;
                            }
                            case 1: {
                                long channelId = data.readInt32(false);
                                int newDialogType = data.readInt32(false);
                                Utilities.stageQueue.postRunnable(() -> getMessagesController().getChannelDifference(channelId, newDialogType, taskId, null));
                                break;
                            }
                            case 2:
                            case 5:
                            case 8:
                            case 10:
                            case 14: {
                                TLRPC.Dialog dialog = new TLRPC.TL_dialog();
                                dialog.id = data.readInt64(false);
                                dialog.top_message = data.readInt32(false);
                                dialog.read_inbox_max_id = data.readInt32(false);
                                dialog.read_outbox_max_id = data.readInt32(false);
                                dialog.unread_count = data.readInt32(false);
                                dialog.last_message_date = data.readInt32(false);
                                dialog.pts = data.readInt32(false);
                                dialog.flags = data.readInt32(false);
                                if (type >= 5) {
                                    dialog.pinned = data.readBool(false);
                                    dialog.pinnedNum = data.readInt32(false);
                                }
                                if (type >= 8) {
                                    dialog.unread_mentions_count = data.readInt32(false);
                                }
                                if (type >= 10) {
                                    dialog.unread_mark = data.readBool(false);
                                }
                                if (type >= 14) {
                                    dialog.folder_id = data.readInt32(false);
                                }
                                TLRPC.InputPeer peer = TLRPC.InputPeer.TLdeserialize(data, data.readInt32(false), false);
                                AndroidUtilities.runOnUIThread(() -> getMessagesController().checkLastDialogMessage(dialog, peer, taskId));
                                break;
                            }
                            case 3: {
                                long random_id = data.readInt64(false);
                                TLRPC.InputPeer peer = TLRPC.InputPeer.TLdeserialize(data, data.readInt32(false), false);
                                TLRPC.TL_inputMediaGame game = (TLRPC.TL_inputMediaGame) TLRPC.InputMedia.TLdeserialize(data, data.readInt32(false), false);
                                getSendMessagesHelper().sendGame(peer, game, random_id, taskId);
                                break;
                            }
                            case 4: {
                                long did = data.readInt64(false);
                                boolean pin = data.readBool(false);
                                TLRPC.InputPeer peer = TLRPC.InputPeer.TLdeserialize(data, data.readInt32(false), false);
                                AndroidUtilities.runOnUIThread(() -> getMessagesController().pinDialog(did, pin, peer, taskId));
                                break;
                            }
                            case 6: {
                                long channelId = data.readInt32(false);
                                int newDialogType = data.readInt32(false);
                                TLRPC.InputChannel inputChannel = TLRPC.InputChannel.TLdeserialize(data, data.readInt32(false), false);
                                Utilities.stageQueue.postRunnable(() -> getMessagesController().getChannelDifference(channelId, newDialogType, taskId, inputChannel));
                                break;
                            }
                            case 25: {
                                long channelId = data.readInt64(false);
                                int newDialogType = data.readInt32(false);
                                TLRPC.InputChannel inputChannel = TLRPC.InputChannel.TLdeserialize(data, data.readInt32(false), false);
                                Utilities.stageQueue.postRunnable(() -> getMessagesController().getChannelDifference(channelId, newDialogType, taskId, inputChannel));
                                break;
                            }
                            case 7: {
                                long channelId = data.readInt32(false);
                                int constructor = data.readInt32(false);
                                TLObject request = TLRPC.TL_messages_deleteMessages.TLdeserialize(data, constructor, false);
                                if (request == null) {
                                    request = TLRPC.TL_channels_deleteMessages.TLdeserialize(data, constructor, false);
                                }
                                if (request == null) {
                                    removePendingTask(taskId);
                                } else {
                                    TLObject finalRequest = request;
                                    AndroidUtilities.runOnUIThread(() -> getMessagesController().deleteMessages(null, null, null, -channelId, true, 0, false, taskId, finalRequest, 0));
                                }
                                break;
                            }
                            case 24: {
                                long dialogId = data.readInt64(false);
                                int constructor = data.readInt32(false);
                                TLObject request = TLRPC.TL_messages_deleteMessages.TLdeserialize(data, constructor, false);
                                if (request == null) {
                                    request = TLRPC.TL_channels_deleteMessages.TLdeserialize(data, constructor, false);
                                }
                                if (request == null) {
                                    removePendingTask(taskId);
                                } else {
                                    TLObject finalRequest = request;
                                    AndroidUtilities.runOnUIThread(() -> getMessagesController().deleteMessages(null, null, null, dialogId, true, 0, false, taskId, finalRequest, 0));
                                }
                                break;
                            }
                            case 103: {
                                long dialogId = data.readInt64(false);
                                int topicId = data.readInt32(false);
                                int constructor = data.readInt32(false);
                                TLObject request = TLRPC.TL_messages_deleteMessages.TLdeserialize(data, constructor, false);
                                if (request == null) {
                                    request = TLRPC.TL_channels_deleteMessages.TLdeserialize(data, constructor, false);
                                }
                                if (request == null) {
                                    removePendingTask(taskId);
                                } else {
                                    TLObject finalRequest = request;
                                    AndroidUtilities.runOnUIThread(() -> getMessagesController().deleteMessages(null, null, null, dialogId, true, 0, false, taskId, finalRequest, topicId));
                                }
                                break;
                            }
                            case 9: {
                                long did = data.readInt64(false);
                                TLRPC.InputPeer peer = TLRPC.InputPeer.TLdeserialize(data, data.readInt32(false), false);
                                AndroidUtilities.runOnUIThread(() -> getMessagesController().markDialogAsUnread(did, peer, taskId));
                                break;
                            }
                            case 11: {
                                TLRPC.InputChannel inputChannel;
                                int mid = data.readInt32(false);
                                long channelId = data.readInt32(false);
                                int ttl = data.readInt32(false);
                                if (channelId != 0) {
                                    inputChannel = TLRPC.InputChannel.TLdeserialize(data, data.readInt32(false), false);
                                } else {
                                    inputChannel = null;
                                }
                                AndroidUtilities.runOnUIThread(() -> getMessagesController().markMessageAsRead2(-channelId, mid, inputChannel, ttl, taskId));
                                break;
                            }
                            case 101:
                            case 23: {
                                TLRPC.InputChannel inputChannel;
                                long dialogId = data.readInt64(false);
                                int mid = data.readInt32(false);
                                int ttl = data.readInt32(false);
                                if (!DialogObject.isEncryptedDialog(dialogId) && DialogObject.isChatDialog(dialogId) && data.hasRemaining()) {
                                    inputChannel = TLRPC.InputChannel.TLdeserialize(data, data.readInt32(false), false);
                                } else {
                                    inputChannel = null;
                                }
                                AndroidUtilities.runOnUIThread(() -> getMessagesController().markMessageAsRead2(dialogId, mid, inputChannel, ttl, taskId, type == 23));
                                break;
                            }
                            case 12:
                            case 19:
                            case 20:
                                removePendingTask(taskId);
                                break;
                            case 21: {
                                Theme.OverrideWallpaperInfo info = new Theme.OverrideWallpaperInfo();
                                long id = data.readInt64(false);
                                info.isBlurred = data.readBool(false);
                                info.isMotion = data.readBool(false);
                                info.color = data.readInt32(false);
                                info.gradientColor1 = data.readInt32(false);
                                info.rotation = data.readInt32(false);
                                info.intensity = (float) data.readDouble(false);
                                boolean install = data.readBool(false);
                                info.slug = data.readString(false);
                                info.originalFileName = data.readString(false);
                                AndroidUtilities.runOnUIThread(() -> getMessagesController().saveWallpaperToServer(null, info, install, taskId));
                                break;
                            }
                            case 13: {
                                long did = data.readInt64(false);
                                boolean first = data.readBool(false);
                                int onlyHistory = data.readInt32(false);
                                int maxIdDelete = data.readInt32(false);
                                boolean revoke = data.readBool(false);
                                TLRPC.InputPeer inputPeer = TLRPC.InputPeer.TLdeserialize(data, data.readInt32(false), false);
                                AndroidUtilities.runOnUIThread(() -> getMessagesController().deleteDialog(did, first ? 1 : 0, onlyHistory, maxIdDelete, revoke, inputPeer, taskId));
                                break;
                            }
                            case 15: {
                                TLRPC.InputPeer inputPeer = TLRPC.InputPeer.TLdeserialize(data, data.readInt32(false), false);
                                Utilities.stageQueue.postRunnable(() -> getMessagesController().loadUnknownDialog(inputPeer, taskId));
                                break;
                            }
                            case 16: {
                                int folderId = data.readInt32(false);
                                int count = data.readInt32(false);
                                ArrayList<TLRPC.InputDialogPeer> peers = new ArrayList<>();
                                for (int a = 0; a < count; a++) {
                                    TLRPC.InputDialogPeer inputPeer = TLRPC.InputDialogPeer.TLdeserialize(data, data.readInt32(false), false);
                                    peers.add(inputPeer);
                                }
                                AndroidUtilities.runOnUIThread(() -> getMessagesController().reorderPinnedDialogs(folderId, peers, taskId));
                                break;
                            }
                            case 17: {
                                int folderId = data.readInt32(false);
                                int count = data.readInt32(false);
                                ArrayList<TLRPC.TL_inputFolderPeer> peers = new ArrayList<>();
                                for (int a = 0; a < count; a++) {
                                    TLRPC.TL_inputFolderPeer inputPeer = TLRPC.TL_inputFolderPeer.TLdeserialize(data, data.readInt32(false), false);
                                    peers.add(inputPeer);
                                }
                                AndroidUtilities.runOnUIThread(() -> getMessagesController().addDialogToFolder(null, folderId, -1, peers, taskId));
                                break;
                            }
                            case 18: {
                                long dialogId = data.readInt64(false);
                                data.readInt32(false);
                                int constructor = data.readInt32(false);
                                TLObject request = TLRPC.TL_messages_deleteScheduledMessages.TLdeserialize(data, constructor, false);
                                if (request == null) {
                                    removePendingTask(taskId);
                                } else {
                                    AndroidUtilities.runOnUIThread(() -> getMessagesController().deleteMessages(null, null, null, dialogId, true, ChatActivity.MODE_SCHEDULED, false, taskId, request, 0));
                                }
                                break;
                            }
                            case 22: {
                                TLRPC.InputPeer inputPeer = TLRPC.InputPeer.TLdeserialize(data, data.readInt32(false), false);
                                AndroidUtilities.runOnUIThread(() -> getMessagesController().reloadMentionsCountForChannel(inputPeer, taskId));
                                break;
                            }
                            case 100: {
                                final int chatId = data.readInt32(false);
                                final boolean revoke = data.readBool(false);
                                AndroidUtilities.runOnUIThread(() -> getSecretChatHelper().declineSecretChat(chatId, revoke, taskId));
                                break;
                            }
                            case 102: {
                                long dialogId = data.readInt64(false);
                                int mid = data.readInt32(false);
                                AndroidUtilities.runOnUIThread(() -> getMessagesController().doDeleteShowOnceTask(taskId, dialogId, mid));
                                break;
                            }
                        }
                        data.reuse();
                    }
                }
                cursor.dispose();
            } catch (Exception e) {
                checkSQLException(e);
            }
        });
    }

    public void saveChannelPts(long channelId, int pts) {
        storageQueue.postRunnable(() -> {
            try {
                SQLitePreparedStatement state = database.executeFast("UPDATE dialogs SET pts = ? WHERE did = ?");
                state.bindInteger(1, pts);
                state.bindLong(2, -channelId);
                state.step();
                state.dispose();
            } catch (Exception e) {
                checkSQLException(e);
            }
        });
    }

    private void saveDiffParamsInternal(int seq, int pts, int date, int qts) {
        try {
            if (lastSavedSeq == seq && lastSavedPts == pts && lastSavedDate == date && lastQtsValue == qts) {
                return;
            }
            SQLitePreparedStatement state = database.executeFast("UPDATE params SET seq = ?, pts = ?, date = ?, qts = ? WHERE id = 1");
            state.bindInteger(1, seq);
            state.bindInteger(2, pts);
            state.bindInteger(3, date);
            state.bindInteger(4, qts);
            state.step();
            state.dispose();
            lastSavedSeq = seq;
            lastSavedPts = pts;
            lastSavedDate = date;
            lastSavedQts = qts;
        } catch (Exception e) {
            checkSQLException(e);
        }
    }

    public void saveDiffParams(int seq, int pts, int date, int qts) {
        storageQueue.postRunnable(() -> saveDiffParamsInternal(seq, pts, date, qts));
    }

    public void updateMutedDialogsFiltersCounters() {
        storageQueue.postRunnable(() -> resetAllUnreadCounters(true));
    }

    public void setDialogFlags(long did, long flags) {
        storageQueue.postRunnable(() -> {
            try {
                int oldFlags = 0;
                SQLiteCursor cursor = database.queryFinalized("SELECT flags FROM dialog_settings WHERE did = " + did);
                if (cursor.next()) {
                    oldFlags = cursor.intValue(0);
                }
                cursor.dispose();
                if (flags == oldFlags) {
                    return;
                }
                database.executeFast(String.format(Locale.US, "REPLACE INTO dialog_settings VALUES(%d, %d)", did, flags)).stepThis().dispose();
                resetAllUnreadCounters(true);
            } catch (Exception e) {
                checkSQLException(e);
            }
        });
    }

    public void putStoryPushMessage(NotificationsController.StoryNotification push) {
        storageQueue.postRunnable(() -> {
            try {
                database.executeFast("DELETE FROM story_pushes WHERE uid = " + push.dialogId).stepThis().dispose();
                SQLitePreparedStatement state = database.executeFast("REPLACE INTO story_pushes VALUES(?, ?, ?, ?, ?, ?)");
                for (Map.Entry<Integer, Pair<Long, Long>> e : push.dateByIds.entrySet()) {
                    int id = e.getKey();
                    long date = e.getValue().first;
                    long expire_date = e.getValue().second;
                    state.requery();
                    state.bindLong(1, push.dialogId);
                    state.bindInteger(2, id);
                    state.bindLong(3, date);
                    if (push.localName == null) {
                        push.localName = "";
                    }
                    state.bindString(4, push.localName);
                    state.bindInteger(5, push.hidden ? 1 : 0);
                    state.bindLong(6, expire_date);
                    state.step();
                }
                state.dispose();
            } catch (Exception e) {
                checkSQLException(e);
            }
        });
    }

    public void deleteStoryPushMessage(long dialogId) {
        storageQueue.postRunnable(() -> {
            try {
                database.executeFast("DELETE FROM story_pushes WHERE uid = " + dialogId).stepThis().dispose();
            } catch (Exception e) {
                checkSQLException(e);
            }
        });
    }

    public void deleteAllStoryPushMessages() {
        storageQueue.postRunnable(() -> {
            try {
                database.executeFast("DELETE FROM story_pushes").stepThis().dispose();
            } catch (Exception e) {
                checkSQLException(e);
            }
        });
    }

    public void deleteAllStoryReactionPushMessages() {
        storageQueue.postRunnable(() -> {
            try {
                database.executeFast("DELETE FROM unread_push_messages WHERE is_reaction = 2").stepThis().dispose();
            } catch (Exception e) {
                checkSQLException(e);
            }
        });
    }

    public void putPushMessage(MessageObject message) {
        storageQueue.postRunnable(() -> {
            try {
                NativeByteBuffer data = new NativeByteBuffer(message.messageOwner.getObjectSize());
                message.messageOwner.serializeToStream(data);

                int flags = 0;
                if (message.localType == 2) {
                    flags |= 1;
                }
                if (message.localChannel) {
                    flags |= 2;
                }

                SQLitePreparedStatement state = database.executeFast("REPLACE INTO unread_push_messages VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                state.requery();
                state.bindLong(1, message.getDialogId());
                state.bindInteger(2, message.getId());
                state.bindLong(3, message.messageOwner.random_id);
                state.bindInteger(4, message.messageOwner.date);
                state.bindByteBuffer(5, data);
                if (message.messageText == null) {
                    state.bindNull(6);
                } else {
                    state.bindString(6, message.messageText.toString());
                }
                if (message.localName == null) {
                    state.bindNull(7);
                } else {
                    state.bindString(7, message.localName);
                }
                if (message.localUserName == null) {
                    state.bindNull(8);
                } else {
                    state.bindString(8, message.localUserName);
                }
                state.bindInteger(9, flags);
                state.bindLong(10, MessageObject.getTopicId(currentAccount, message.messageOwner, false));
                state.bindInteger(11, (message.isReactionPush ? 1 : 0) + (message.isStoryReactionPush ? 1 : 0));
                state.step();

                data.reuse();
                state.dispose();
            } catch (Exception e) {
                checkSQLException(e);
            }
        });
    }

    public void clearLocalDatabase() {
        storageQueue.postRunnable(() -> {
            SQLiteCursor cursor = null;
            SQLitePreparedStatement state5 = null;
            SQLitePreparedStatement state6 = null;
            try {
                ArrayList<Long> dialogsToCleanup = new ArrayList<>();

                database.executeFast("DELETE FROM poll_votes_mentions").stepThis().dispose();
                database.executeFast("DELETE FROM poll_votes_mentions_topics").stepThis().dispose();
                database.executeFast("DELETE FROM reaction_mentions").stepThis().dispose();
                database.executeFast("DELETE FROM reaction_mentions_topics").stepThis().dispose();
                database.executeFast("DELETE FROM downloading_documents").stepThis().dispose();
                database.executeFast("DELETE FROM attach_menu_bots").stepThis().dispose();
                database.executeFast("DELETE FROM animated_emoji").stepThis().dispose();
                database.executeFast("DELETE FROM stickers_v2").stepThis().dispose();
                database.executeFast("DELETE FROM stickersets2").stepThis().dispose();
                database.executeFast("DELETE FROM messages_holes_topics").stepThis().dispose();
                database.executeFast("DELETE FROM messages_topics").stepThis().dispose();
                database.executeFast("DELETE FROM saved_dialogs").stepThis().dispose();
                database.executeFast("DELETE FROM topics").stepThis().dispose();
                database.executeFast("DELETE FROM media_holes_topics").stepThis().dispose();
                database.executeFast("DELETE FROM media_topics").stepThis().dispose();
                database.executeFast("DELETE FROM media_counts_topics").stepThis().dispose();
                database.executeFast("DELETE FROM chat_pinned_v2").stepThis().dispose();
                database.executeFast("DELETE FROM chat_pinned_count").stepThis().dispose();
                database.executeFast("DELETE FROM profile_stories").stepThis().dispose();
                database.executeFast("DELETE FROM profile_stories_albums").stepThis().dispose();
                database.executeFast("DELETE FROM profile_stories_albums_links").stepThis().dispose();
                database.executeFast("DELETE FROM story_pushes").stepThis().dispose();
                database.executeFast("DELETE FROM dialog_photos").stepThis().dispose();
                database.executeFast("DELETE FROM dialog_photos_count").stepThis().dispose();
                database.executeFast("DELETE FROM saved_reaction_tags").stepThis().dispose();
                database.executeFast("DELETE FROM business_replies").stepThis().dispose();
                database.executeFast("DELETE FROM quick_replies_messages").stepThis().dispose();
                database.executeFast("DELETE FROM effects").stepThis().dispose();
                database.executeFast("DELETE FROM app_config").stepThis().dispose();
                database.executeFast("DELETE FROM star_gifts2").stepThis().dispose();
                database.executeFast("DELETE FROM premium_promo").stepThis().dispose();
                database.executeFast("DELETE FROM media_counts_v2").stepThis().dispose();
                database.executeFast("DELETE FROM media_v4").stepThis().dispose();


                cursor = database.queryFinalized("SELECT did FROM dialogs WHERE 1");
                while (cursor.next()) {
                    long did = cursor.longValue(0);
                    if (!DialogObject.isEncryptedDialog(did)) {
                        dialogsToCleanup.add(did);
                    }
                }
                cursor.dispose();
                cursor = null;

                state5 = database.executeFast("REPLACE INTO messages_holes VALUES(?, ?, ?)");
                state6 = database.executeFast("REPLACE INTO media_holes_v2 VALUES(?, ?, ?, ?)");

                database.beginTransaction();
                for (int a = 0; a < dialogsToCleanup.size(); a++) {
                    Long did = dialogsToCleanup.get(a);
                    int messagesCount = 0;
                    cursor = database.queryFinalized("SELECT COUNT(mid) FROM messages_v2 WHERE uid = " + did);
                    if (cursor.next()) {
                        messagesCount = cursor.intValue(0);
                    }
                    cursor.dispose();
                    if (messagesCount <= 2) {
                        continue;
                    }

                    cursor = database.queryFinalized("SELECT last_mid_i, last_mid FROM dialogs WHERE did = " + did);
                    int messageId = -1;
                    if (cursor.next()) {
                        long last_mid_i = cursor.longValue(0);
                        long last_mid = cursor.longValue(1);
                        SQLiteCursor cursor2 = database.queryFinalized("SELECT data FROM messages_v2 WHERE uid = " + did + " AND mid IN (" + last_mid_i + "," + last_mid + ")");
                        try {
                            while (cursor2.next()) {
                                NativeByteBuffer data = cursor2.byteBufferValue(0);
                                if (data != null) {
                                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                                    if (message != null) {
                                        messageId = message.id;
                                        message.readAttachPath(data, UserConfig.getInstance(currentAccount).clientUserId);
                                    }
                                    data.reuse();
                                }
                            }
                        } catch (Exception e) {
                            checkSQLException(e);
                        }
                        cursor2.dispose();

                        database.executeFast("DELETE FROM messages_v2 WHERE uid = " + did + " AND mid != " + last_mid_i + " AND mid != " + last_mid).stepThis().dispose();
                        database.executeFast("DELETE FROM messages_holes WHERE uid = " + did).stepThis().dispose();
                        database.executeFast("DELETE FROM bot_keyboard WHERE uid = " + did).stepThis().dispose();
                        database.executeFast("DELETE FROM bot_keyboard_topics WHERE uid = " + did).stepThis().dispose();
                        database.executeFast("DELETE FROM media_counts_v2 WHERE uid = " + did).stepThis().dispose();
                        database.executeFast("DELETE FROM media_v4 WHERE uid = " + did).stepThis().dispose();
                        database.executeFast("DELETE FROM media_holes_v2 WHERE uid = " + did).stepThis().dispose();
                        MediaDataController.getInstance(currentAccount).clearBotKeyboard(did);
                        if (messageId != -1) {
                            MessagesStorage.createFirstHoles(did, state5, state6, messageId, 0);
                        }
                    }
                    cursor.dispose();
                    cursor = null;
                }

                state5.dispose();
                state6.dispose();
                state5 = null;
                state6 = null;
                database.commitTransaction();
                database.executeFast("PRAGMA journal_size_limit = 0").stepThis().dispose();
                database.executeFast("VACUUM").stepThis().dispose();
                database.executeFast("PRAGMA journal_size_limit = -1").stepThis().dispose();

                getMessagesController().getTopicsController().databaseCleared();
                AndroidUtilities.runOnUIThread(() -> {
                    getMessagesController().getSavedMessagesController().cleanup();
                });
            } catch (Exception e) {
                checkSQLException(e);
            } finally {
                if (database != null) {
                    database.commitTransaction();
                }
                if (state5 != null) {
                    state5.dispose();
                }
                if (state6 != null) {
                    state6.dispose();
                }
                if (cursor != null) {
                    cursor.dispose();
                }
                reset();
            }
        });
    }

    public void updateRanksInLastMessages(long dialogId, long userId, String rank) {
        storageQueue.postRunnable(() -> {
            final ArrayList<Pair<Integer, TLRPC.Message>> messagesToUpdate = new ArrayList<>();
            SQLiteCursor cursor = null;
            SQLitePreparedStatement state = null;
            try {
                cursor = database.queryFinalized(String.format(Locale.US, "SELECT mid, data FROM messages_v2 WHERE uid = %s ORDER BY date DESC LIMIT 20", dialogId));
                while (cursor.next()) {
                    final int messageId = cursor.intValue(0);
                    final NativeByteBuffer data = cursor.byteBufferValue(1);
                    final TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message != null) {
                        message.readAttachPath(data, UserConfig.getInstance(currentAccount).clientUserId);
                    }
                    if (DialogObject.getPeerDialogId(message.from_id) == userId) {
                        message.flags2 |= TLObject.FLAG_12;
                        message.from_rank = rank;
                        messagesToUpdate.add(new Pair<>(messageId, message));
                    }
                }
                if (cursor != null) {
                    cursor.dispose();
                }
                for (int i = 0; i < messagesToUpdate.size(); ++i) {
                    final int messageId = messagesToUpdate.get(i).first;
                    final TLRPC.Message message = messagesToUpdate.get(i).second;
                    state = database.executeFast("UPDATE messages_v2 SET data = ? WHERE mid = ? AND uid = ?");
                    state.requery();
                    NativeByteBuffer data = new NativeByteBuffer(message.getObjectSize());
                    MessageObject.normalizeFlags(message);
                    message.serializeToStream(data);
                    state.bindByteBuffer(1, data);
                    state.bindInteger(2, messageId);
                    state.bindLong(3, dialogId);
                    state.step();
                    state.dispose();
                    state = null;
                    data.reuse();
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            final boolean updated = messagesToUpdate.size() > 0;
            if (updated) {
                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, 0);
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updatedChatRanks, -dialogId, userId, rank);
                });
            }
        });
    }

    public void saveTopics(long dialogId, List<TLRPC.TL_forumTopic> topics, boolean replace, boolean useQueue, int date) {
        if (useQueue) {
            storageQueue.postRunnable(() -> {
                saveTopicsInternal(dialogId, topics, replace, true, date);
            });
        } else {
            saveTopicsInternal(dialogId, topics, replace, false, date);
        }
    }

    private void saveTopicsInternal(long dialogId, List<TLRPC.TL_forumTopic> topics, boolean replace, boolean inTransaction, int date) {
        SQLitePreparedStatement state = null;
        try {
            HashSet<Integer> existingTopics = new HashSet<>();
            HashMap<Integer, Integer> pinnedValues = new HashMap<>();
            for (int i = 0; i < topics.size(); i++) {
                TLRPC.TL_forumTopic topic = topics.get(i);
                SQLiteCursor cursor = database.queryFinalized("SELECT did, pinned FROM topics WHERE did = " + dialogId + " AND topic_id = " + topic.id);
                boolean exist = cursor.next();
                if (exist) {
                    pinnedValues.put(i, cursor.intValue(2));
                }
                cursor.dispose();
                cursor = null;
                if (exist) {
                    existingTopics.add(i);
                }
            }
            if (replace) {
                database.executeFast("DELETE FROM topics WHERE did = " + dialogId).stepThis().dispose();
            }
            state = database.executeFast("REPLACE INTO topics VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            if (inTransaction) {
                database.beginTransaction();
            }

            for (int i = 0; i < topics.size(); i++) {
                TLRPC.TL_forumTopic topic = topics.get(i);
                long topicId = isMonoForum(dialogId) ? DialogObject.getPeerDialogId(topic.from_id): topic.id;
                boolean exist = existingTopics.contains(i);

                state.requery();
                state.bindLong(1, dialogId);
                state.bindLong(2, topicId);
                NativeByteBuffer data = new NativeByteBuffer(topic.getObjectSize());
                topic.serializeToStream(data);

                state.bindByteBuffer(3, data);
                state.bindInteger(4, topic.top_message);

                NativeByteBuffer messageData = new NativeByteBuffer(topic.topicStartMessage.getObjectSize());
                topic.topicStartMessage.serializeToStream(messageData);
                state.bindByteBuffer(5, messageData);
                state.bindInteger(6, topic.unread_count);
                state.bindInteger(7, topic.read_inbox_max_id);
                state.bindInteger(8, topic.unread_mentions_count);
                state.bindInteger(9, topic.unread_reactions_count);
                state.bindInteger(10, topic.read_outbox_max_id);
                if (topic.isShort && pinnedValues.containsKey(i)) {
                    state.bindInteger(11, pinnedValues.get(i));
                } else {
                    state.bindInteger(11, topic.pinned ? 1 + topic.pinnedOrder : 0);
                }
                state.bindInteger(12, topic.totalMessagesCount);
                state.bindInteger(13, topic.hidden ? 1 : 0);
                state.bindInteger(14, date);
                state.bindInteger(15, topic.nopaid_messages_exception ? 1 : 0);
                state.bindInteger(16, topic.unread_poll_votes_count);

                state.step();
                messageData.reuse();
                data.reuse();

                if (exist) {
                    closeHolesInTable("messages_holes_topics", dialogId, topic.top_message, topic.top_message, topicId);
                    closeHolesInMedia(dialogId, topic.top_message, topic.top_message, -1, 0);
                } else {
                    database.executeFast(String.format(Locale.ENGLISH, "DELETE FROM messages_holes_topics WHERE uid = %d AND topic_id = %d", dialogId, topicId)).stepThis().dispose();
                    database.executeFast(String.format(Locale.ENGLISH, "DELETE FROM media_holes_topics WHERE uid = %d AND topic_id = %d", dialogId, topicId)).stepThis().dispose();
                    database.executeFast(String.format(Locale.ENGLISH, "DELETE FROM messages_topics WHERE uid = %d AND topic_id = %d", dialogId, topicId)).stepThis().dispose();
                    database.executeFast(String.format(Locale.ENGLISH, "DELETE FROM media_topics WHERE uid = %d AND topic_id = %d", dialogId, topicId)).stepThis().dispose();

                    SQLitePreparedStatement state_holes = database.executeFast("REPLACE INTO messages_holes_topics VALUES(?, ?, ?, ?)");
                    SQLitePreparedStatement state_media_holes = database.executeFast("REPLACE INTO media_holes_topics VALUES(?, ?, ?, ?, ?)");
                    createFirstHoles(dialogId, state_holes, state_media_holes, topic.top_message, topicId);
                    state_holes.dispose();
                    state_holes.dispose();
                }
            }
            resetAllUnreadCounters(false);

        } catch (Exception e) {
            checkSQLException(e);

        } finally {
            if (state != null) {
                state.dispose();
            }
            database.commitTransaction();
        }
    }

    public void updateTopicData(long dialogId, TLRPC.TL_forumTopic fromTopic, int flags) {
        updateTopicData(dialogId, fromTopic, flags, getConnectionsManager().getCurrentTime());
    }

    public void updateTopicData(long dialogId, TLRPC.TL_forumTopic fromTopic, int flags, int date) {
        if (fromTopic == null) {
            return;
        }
        storageQueue.postRunnable(() -> {
            SQLitePreparedStatement state = null;
            SQLiteCursor cursor = null;
            try {
                if ((flags & TopicsController.TOPIC_FLAG_TOTAL_MESSAGES_COUNT) != 0) {
                    state = database.executeFast("UPDATE topics SET total_messages_count = ? WHERE did = ? AND topic_id = ?");
                    state.requery();
                    state.bindInteger(1, fromTopic.totalMessagesCount);
                    state.bindLong(2, dialogId);
                    state.bindLong(3, isMonoForum(dialogId) ? DialogObject.getPeerDialogId(fromTopic.from_id) : fromTopic.id);
                    state.step();
                    state.dispose();
                    if (flags == TopicsController.TOPIC_FLAG_TOTAL_MESSAGES_COUNT) {
                        return;
                    }
                }
                int currentEditDate = 0;
                TLRPC.TL_forumTopic topicToUpdate = null;
                cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, edit_date FROM topics WHERE did = %d AND topic_id = %d", dialogId, fromTopic.id));
                if (cursor.next()) {
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    currentEditDate = cursor.intValue(1);
                    if (data != null) {
                        topicToUpdate = TLRPC.TL_forumTopic.TLdeserialize(data, data.readInt32(true), true);
                        data.reuse();
                    }
                }
                cursor.dispose();
                cursor = null;

                if (topicToUpdate != null && (currentEditDate == 0 || currentEditDate <= date)) {
                    if ((flags & TopicsController.TOPIC_FLAG_TITLE) != 0) {
                        topicToUpdate.title = fromTopic.title;
                    }
                    if ((flags & TopicsController.TOPIC_FLAG_ICON) != 0) {
                        topicToUpdate.icon_emoji_id = fromTopic.icon_emoji_id;
                        topicToUpdate.flags |= 1;
                    }
                    if ((flags & TopicsController.TOPIC_FLAG_PIN) != 0) {
                        topicToUpdate.pinned = fromTopic.pinned;
                        topicToUpdate.pinnedOrder = fromTopic.pinnedOrder;
                    }
                    int pinnedOrder = topicToUpdate.pinned ? 1 + topicToUpdate.pinnedOrder : 0;
                    if ((flags & TopicsController.TOPIC_FLAG_CLOSE) != 0) {
                        topicToUpdate.closed = fromTopic.closed;
                    }
                    if ((flags & TopicsController.TOPIC_FLAG_HIDE) != 0) {
                        topicToUpdate.hidden = fromTopic.hidden;
                    }
                    state = database.executeFast("UPDATE topics SET data = ?, pinned = ?, hidden = ?, edit_date = ? WHERE did = ? AND topic_id = ?");
                    database.beginTransaction();
                    NativeByteBuffer data = new NativeByteBuffer(topicToUpdate.getObjectSize());
                    topicToUpdate.serializeToStream(data);
                    state.bindByteBuffer(1, data);
                    state.bindInteger(2, pinnedOrder);
                    state.bindInteger(3, topicToUpdate.hidden ? 1 : 0);
                    state.bindInteger(4, date);
                    state.bindLong(5, dialogId);
                    state.bindLong(6, topicToUpdate.id);
                    state.step();
                    data.reuse();

                    int finalFlags = flags;
                    AndroidUtilities.runOnUIThread(() -> {
                        getMessagesController().getTopicsController().updateTopicInUi(dialogId, fromTopic, finalFlags);
                    });
                }
            } catch (Exception e) {
                checkSQLException(e);
            } finally {
                if (state != null) {
                    state.dispose();
                }
                if (cursor != null) {
                    cursor.dispose();
                }
                database.commitTransaction();
            }
        });
    }

    public void loadTopics(long dialogId, Consumer<ArrayList<TLRPC.TL_forumTopic>> callback) {
        storageQueue.postRunnable(() -> {
            ArrayList<TLRPC.TL_forumTopic> topics = null;
            SQLiteCursor cursor = null;
            try {
                cursor = database.queryFinalized(String.format(Locale.US, "SELECT top_message, data, topic_message, unread_count, max_read_id, unread_mentions, unread_reactions, read_outbox, pinned, total_messages_count, nopaid_messages_exception, unread_poll_votes FROM topics WHERE did = %d ORDER BY pinned ASC", dialogId));

                SparseArray<ArrayList<TLRPC.TL_forumTopic>> topicsByTopMessageId = null;
                HashSet<Integer> topMessageIds = null;
                while (cursor.next()) {
                    if (topics == null) {
                        topics = new ArrayList<>();
                        topicsByTopMessageId = new SparseArray<>();
                        topMessageIds = new HashSet<>();
                    }
                    int topMessageId = cursor.intValue(0);
                    NativeByteBuffer data = cursor.byteBufferValue(1);
                    if (data != null) {
                        TLRPC.TL_forumTopic topic = TLRPC.TL_forumTopic.TLdeserialize(data, data.readInt32(false), false);
                        if (topic != null) {
                            topic.top_message = topMessageId;
                            ArrayList<TLRPC.TL_forumTopic> topicsListByTopMessageId = topicsByTopMessageId.get(topMessageId);
                            if (topicsListByTopMessageId == null) {
                                topicsListByTopMessageId = new ArrayList<>();
                                topicsByTopMessageId.put(topMessageId, topicsListByTopMessageId);
                            }
                            topicsListByTopMessageId.add(topic);
                            topMessageIds.add(topMessageId);
                            topics.add(topic);

                            NativeByteBuffer data2 = cursor.byteBufferValue(2);
                            //if (data2 != null) {
                                topic.topicStartMessage = TLRPC.Message.TLdeserialize(data2, data2.readInt32(false), false);
                                if (data2 != null) {
                                    data2.reuse();
                                }
                           // }
                            topic.unread_count = cursor.intValue(3);
                            topic.read_inbox_max_id = cursor.intValue(4);
                            topic.unread_mentions_count = cursor.intValue(5);
                            topic.unread_reactions_count = cursor.intValue(6);
                            topic.read_outbox_max_id = cursor.intValue(7);
                            topic.pinnedOrder = cursor.intValue(8) - 1;
                            topic.pinned = topic.pinnedOrder >= 0;
                            topic.totalMessagesCount = cursor.intValue(9);
                            topic.nopaid_messages_exception = cursor.intValue(10) != 0;
                            topic.unread_poll_votes_count = cursor.intValue(11);
                        }

                        data.reuse();
                    }
                }
                ArrayList<Long> usersToLoad = new ArrayList<>();
                ArrayList<Long> chatsToLoad = new ArrayList<>();
                LongSparseArray<SparseArray<ArrayList<TLRPC.Message>>> replyMessageOwners = new LongSparseArray<>();
                LongSparseArray<ArrayList<Integer>> dialogReplyMessagesIds = new LongSparseArray<>();


                if (topics != null && !topics.isEmpty()) {
                    SQLiteCursor cursor2 = database.queryFinalized("SELECT mid, data, replydata FROM messages_v2 WHERE uid = " + dialogId + " AND mid IN (" + TextUtils.join(",", topMessageIds) + ")");
                    while (cursor2.next()) {
                        int messageId = cursor2.intValue(0);
                        NativeByteBuffer data = cursor2.byteBufferValue(1);
                        if (data != null) {
                            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                            if (message != null) {
                                message.readAttachPath(data, UserConfig.getInstance(currentAccount).clientUserId);
                            }
                            data.reuse();

                            topMessageIds.remove(messageId);
                            ArrayList<TLRPC.TL_forumTopic> topicsList = topicsByTopMessageId.get(messageId);
                            if (topicsList != null) {
                                for (int i = 0; i < topicsList.size(); i++) {
                                    topicsList.get(i).topMessage = message;
                                }
                            }

                            addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);

                            try {
                                if (message != null && message.reply_to != null && message.reply_to.reply_to_msg_id != 0 && isMessageActionTypeWithReply(message.action)) {
                                    if (!cursor2.isNull(2)) {
                                        NativeByteBuffer data2 = cursor2.byteBufferValue(2);
                                        if (data2 != null) {
                                            message.replyMessage = TLRPC.Message.TLdeserialize(data2, data2.readInt32(false), false);
                                            message.replyMessage.readAttachPath(data2, getUserConfig().clientUserId);
                                            data2.reuse();
                                            if (message.replyMessage != null) {
                                                addUsersAndChatsFromMessage(message.replyMessage, usersToLoad, chatsToLoad, null);
                                            }
                                        }
                                    }
                                    if (message.replyMessage == null) {
                                        addReplyMessages(message, replyMessageOwners, dialogReplyMessagesIds);
                                    }
                                }
                            } catch (Exception e) {
                                checkSQLException(e);
                            }
                        }
                    }

                    cursor2.dispose();
                    if (!topMessageIds.isEmpty()) {
                        cursor2 = database.queryFinalized("SELECT mid, data FROM messages_topics WHERE uid = " + dialogId + " AND mid IN (" + TextUtils.join(",", topMessageIds) + ")");
                        try {
                            while (cursor2.next()) {
                                int messageId = cursor2.intValue(0);
                                NativeByteBuffer data = cursor2.byteBufferValue(1);
                                if (data != null) {
                                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                                    if (message != null) {
                                        message.readAttachPath(data, UserConfig.getInstance(currentAccount).clientUserId);
                                    }
                                    data.reuse();

                                    topMessageIds.remove(messageId);
                                    addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);

                                    ArrayList<TLRPC.TL_forumTopic> topicsList = topicsByTopMessageId.get(messageId);
                                    if (topicsList != null) {
                                        for (int i = 0; i < topicsList.size(); i++) {
                                            topicsList.get(i).topMessage = message;
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            checkSQLException(e);
                        }
                    }
                    for (TLRPC.TL_forumTopic topic : topics) {
                        long fromId = DialogObject.getPeerDialogId(topic.from_id);
                        if (fromId == 0) {
                