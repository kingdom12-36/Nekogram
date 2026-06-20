package tw.nekomimi.nekogram.helpers;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Nekogram: Stores message edit history locally.
 * Ported from TeleVip-LSPosed (SaveEditsHistory).
 */
public class MessageEditsDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "neko_edits.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "edits";

    private static volatile MessageEditsDatabase instance;

    public static MessageEditsDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (MessageEditsDatabase.class) {
                if (instance == null) {
                    instance = new MessageEditsDatabase(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private MessageEditsDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "dialog_id INTEGER NOT NULL, " +
                "message_id INTEGER NOT NULL, " +
                "text TEXT NOT NULL, " +
                "date INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edits ON " + TABLE + " (dialog_id, message_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public void addEdit(long dialogId, int messageId, String text, long date) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("dialog_id", dialogId);
            cv.put("message_id", messageId);
            cv.put("text", text);
            cv.put("date", date);
            getWritableDatabase().insert(TABLE, null, cv);
        } catch (Exception ignored) {}
    }

    public List<EditEntry> getEdits(long dialogId, int messageId) {
        List<EditEntry> result = new ArrayList<>();
        try {
            Cursor c = getReadableDatabase().query(TABLE, new String[]{"text", "date"},
                    "dialog_id=? AND message_id=?",
                    new String[]{String.valueOf(dialogId), String.valueOf(messageId)},
                    null, null, "id ASC");
            while (c.moveToNext()) result.add(new EditEntry(c.getString(0), c.getLong(1)));
            c.close();
        } catch (Exception ignored) {}
        return result;
    }

    public boolean hasEdits(long dialogId, int messageId) {
        try {
            Cursor c = getReadableDatabase().query(TABLE, new String[]{"COUNT(*)"},
                    "dialog_id=? AND message_id=?",
                    new String[]{String.valueOf(dialogId), String.valueOf(messageId)},
                    null, null, null);
            if (c.moveToNext()) {
                boolean has = c.getInt(0) > 0;
                c.close();
                return has;
            }
            c.close();
        } catch (Exception ignored) {}
        return false;
    }

    public static class EditEntry {
        public final String text;
        public final long date;

        public EditEntry(String text, long date) {
            this.text = text;
            this.date = date;
        }
    }
}
