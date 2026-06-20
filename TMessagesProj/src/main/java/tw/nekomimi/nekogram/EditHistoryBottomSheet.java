package tw.nekomimi.nekogram;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import tw.nekomimi.nekogram.helpers.MessageEditsDatabase;

/**
 * Nekogram: Bottom sheet that displays the locally saved edit history of a message.
 * Shown when the user taps "Edit History" in the message long-press context menu
 * (only visible when Save Edits History is enabled in Chat Settings).
 */
public class EditHistoryBottomSheet extends BottomSheet {

    public EditHistoryBottomSheet(BaseFragment fragment, MessageObject messageObject) {
        super(fragment.getContext(), false, fragment.getResourceProvider());

        Context context = fragment.getContext();
        List<MessageEditsDatabase.EditEntry> edits = MessageEditsDatabase.getInstance(context)
                .getEdits(messageObject.getDialogId(), messageObject.getId());

        setTitle(LocaleController.getString(R.string.NekoEditHistory), true);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(20));

        if (edits.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(LocaleController.getString(R.string.NekoEditHistory_Empty));
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
            empty.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(24), AndroidUtilities.dp(20), AndroidUtilities.dp(8));
            container.addView(empty);
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("d MMM yyyy \u00b7 HH:mm", Locale.getDefault());
            for (int i = 0; i < edits.size(); i++) {
                MessageEditsDatabase.EditEntry entry = edits.get(i);

                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(
                        AndroidUtilities.dp(20), AndroidUtilities.dp(i == 0 ? 12 : 8),
                        AndroidUtilities.dp(20), AndroidUtilities.dp(8));

                TextView dateView = new TextView(context);
                dateView.setText(sdf.format(new Date(entry.date * 1000L)));
                dateView.setTextSize(11);
                dateView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourcesProvider));
                row.addView(dateView);

                TextView textView = new TextView(context);
                textView.setText(entry.text);
                textView.setTextSize(15);
                textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
                textView.setPadding(0, AndroidUtilities.dp(2), 0, 0);
                row.addView(textView);

                if (i < edits.size() - 1) {
                    View divider = new View(context);
                    divider.setBackgroundColor(Theme.getColor(Theme.key_divider, resourcesProvider));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1);
                    lp.setMargins(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), 0);
                    container.addView(divider, lp);
                }

                container.addView(row);
            }
        }

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(container);
        setCustomView(scrollView);
    }
}
