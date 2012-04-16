/**
 * Copyright (c) 2011, Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mail.compose;

import android.app.ActionBar;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ActionBar.OnNavigationListener;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Parcelable;
import android.provider.BaseColumns;
import android.text.Editable;
import android.text.Html;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.util.Rfc822Token;
import android.text.util.Rfc822Tokenizer;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.view.inputmethod.BaseInputConnection;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.common.Rfc822Validator;
import com.android.mail.compose.AttachmentsView.AttachmentDeletedListener;
import com.android.mail.compose.AttachmentsView.AttachmentFailureException;
import com.android.mail.compose.FromAddressSpinner.OnAccountChangedListener;
import com.android.mail.compose.QuotedTextView.RespondInlineListener;
import com.android.mail.providers.Account;
import com.android.mail.providers.Address;
import com.android.mail.providers.Attachment;
import com.android.mail.providers.Message;
import com.android.mail.providers.MessageModification;
import com.android.mail.providers.ReplyFromAccount;
import com.android.mail.providers.Settings;
import com.android.mail.providers.UIProvider;
import com.android.mail.providers.UIProvider.AccountCapabilities;
import com.android.mail.providers.UIProvider.DraftType;
import com.android.mail.R;
import com.android.mail.utils.AccountUtils;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;
import com.android.ex.chips.RecipientEditTextView;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.json.JSONException;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ComposeActivity extends Activity implements OnClickListener, OnNavigationListener,
        RespondInlineListener, DialogInterface.OnClickListener, TextWatcher,
        AttachmentDeletedListener, OnAccountChangedListener {
    // Identifiers for which type of composition this is
    static final int COMPOSE = -1;
    static final int REPLY = 0;
    static final int REPLY_ALL = 1;
    static final int FORWARD = 2;
    static final int EDIT_DRAFT = 3;

    // Integer extra holding one of the above compose action
    private static final String EXTRA_ACTION = "action";

    private static final String UTF8_ENCODING_NAME = "UTF-8";

    private static final String MAIL_TO = "mailto";

    private static final String EXTRA_SUBJECT = "subject";

    private static final String EXTRA_BODY = "body";

    // Extra that we can get passed from other activities
    private static final String EXTRA_TO = "to";
    private static final String EXTRA_CC = "cc";
    private static final String EXTRA_BCC = "bcc";

    // List of all the fields
    static final String[] ALL_EXTRAS = { EXTRA_SUBJECT, EXTRA_BODY, EXTRA_TO, EXTRA_CC, EXTRA_BCC };

    private static SendOrSaveCallback sTestSendOrSaveCallback = null;
    // Map containing information about requests to create new messages, and the id of the
    // messages that were the result of those requests.
    //
    // This map is used when the activity that initiated the save a of a new message, is killed
    // before the save has completed (and when we know the id of the newly created message).  When
    // a save is completed, the service that is running in the background, will update the map
    //
    // When a new ComposeActivity instance is created, it will attempt to use the information in
    // the previously instantiated map.  If ComposeActivity.onCreate() is called, with a bundle
    // (restoring data from a previous instance), and the map hasn't been created, we will attempt
    // to populate the map with data stored in shared preferences.
    private static ConcurrentHashMap<Integer, Long> sRequestMessageIdMap = null;
    // Key used to store the above map
    private static final String CACHED_MESSAGE_REQUEST_IDS_KEY = "cache-message-request-ids";
    /**
     * Notifies the {@code Activity} that the caller is an Email
     * {@code Activity}, so that the back behavior may be modified accordingly.
     *
     * @see #onAppUpPressed
     */
    private static final String EXTRA_FROM_EMAIL_TASK = "fromemail";

    static final String EXTRA_ATTACHMENTS = "attachments";

    //  If this is a reply/forward then this extra will hold the original message
    private static final String EXTRA_IN_REFERENCE_TO_MESSAGE = "in-reference-to-message";
    // If this is an action to edit an existing draft messagge, this extra will hold the
    // draft message
    private static final String ORIGINAL_DRAFT_MESSAGE = "original-draft-message";
    private static final String END_TOKEN = ", ";
    private static final String LOG_TAG = new LogUtils().getLogTag();
    // Request numbers for activities we start
    private static final int RESULT_PICK_ATTACHMENT = 1;
    private static final int RESULT_CREATE_ACCOUNT = 2;
    // TODO(mindyp) set mime-type for auto send?
    private static final String AUTO_SEND_ACTION = "com.android.mail.action.AUTO_SEND";

    // Max size for attachments (5 megs). Will be overridden by account settings if found.
    // TODO(mindyp): read this from account settings?
    private static final int DEFAULT_MAX_ATTACHMENT_SIZE = 25 * 1024 * 1024;

    /**
     * A single thread for running tasks in the background.
     */
    private Handler mSendSaveTaskHandler = null;
    private RecipientEditTextView mTo;
    private RecipientEditTextView mCc;
    private RecipientEditTextView mBcc;
    private Button mCcBccButton;
    private CcBccView mCcBccView;
    private AttachmentsView mAttachmentsView;
    private Account mAccount;
    private ReplyFromAccount mReplyFromAccount;
    private Settings mCachedSettings;
    private Rfc822Validator mValidator;
    private TextView mSubject;

    private ComposeModeAdapter mComposeModeAdapter;
    private int mComposeMode = -1;
    private boolean mForward;
    private String mRecipient;
    private QuotedTextView mQuotedTextView;
    private EditText mBodyView;
    private View mFromStatic;
    private TextView mFromStaticText;
    private View mFromSpinnerWrapper;
    private FromAddressSpinner mFromSpinner;
    private boolean mAddingAttachment;
    private boolean mAttachmentsChanged;
    private boolean mTextChanged;
    private boolean mReplyFromChanged;
    private MenuItem mSave;
    private MenuItem mSend;
    private AlertDialog mRecipientErrorDialog;
    private AlertDialog mSendConfirmDialog;
    private Message mRefMessage;
    private long mDraftId = UIProvider.INVALID_MESSAGE_ID;
    private Message mDraft;
    private Object mDraftLock = new Object();
    private ImageView mAttachmentsButton;

    /**
     * Can be called from a non-UI thread.
     */
    public static void editDraft(Context launcher, Account account, Message message) {
        launch(launcher, account, message, EDIT_DRAFT);
    }

    /**
     * Can be called from a non-UI thread.
     */
    public static void compose(Context launcher, Account account) {
        launch(launcher, account, null, COMPOSE);
    }

    /**
     * Can be called from a non-UI thread.
     */
    public static void reply(Context launcher, Account account, Message message) {
        launch(launcher, account, message, REPLY);
    }

    /**
     * Can be called from a non-UI thread.
     */
    public static void replyAll(Context launcher, Account account, Message message) {
        launch(launcher, account, message, REPLY_ALL);
    }

    /**
     * Can be called from a non-UI thread.
     */
    public static void forward(Context launcher, Account account, Message message) {
        launch(launcher, account, message, FORWARD);
    }

    private static void launch(Context launcher, Account account, Message message, int action) {
        Intent intent = new Intent(launcher, ComposeActivity.class);
        intent.putExtra(EXTRA_FROM_EMAIL_TASK, true);
        intent.putExtra(EXTRA_ACTION, action);
        intent.putExtra(Utils.EXTRA_ACCOUNT, account);
        if (action == EDIT_DRAFT) {
            intent.putExtra(ORIGINAL_DRAFT_MESSAGE, message);
        } else {
            intent.putExtra(EXTRA_IN_REFERENCE_TO_MESSAGE, message);
        }
        launcher.startActivity(intent);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.compose);
        findViews();
        Intent intent = getIntent();

        Account account = (Account)intent.getParcelableExtra(Utils.EXTRA_ACCOUNT);
        if (account == null) {
            final Account[] syncingAccounts = AccountUtils.getSyncingAccounts(this);
            if (syncingAccounts.length > 0) {
                account = syncingAccounts[0];
            }
        }

        setAccount(account);
        if (mAccount == null) {
            return;
        }
        int action = intent.getIntExtra(EXTRA_ACTION, COMPOSE);
        mRefMessage = (Message) intent.getParcelableExtra(EXTRA_IN_REFERENCE_TO_MESSAGE);
        if ((action == REPLY || action == REPLY_ALL || action == FORWARD)) {
            if (mRefMessage != null) {
                initFromRefMessage(action, mAccount.name);
            }
        } else if (action == EDIT_DRAFT) {
            // Initialize the message from the message in the intent
            final Message message = (Message) intent.getParcelableExtra(ORIGINAL_DRAFT_MESSAGE);

            initFromMessage(message);

            // Update the action to the draft type of the previous draft
            switch (message.draftType) {
                case UIProvider.DraftType.REPLY:
                    action = REPLY;
                    break;
                case UIProvider.DraftType.REPLY_ALL:
                    action = REPLY_ALL;
                    break;
                case UIProvider.DraftType.FORWARD:
                    action = FORWARD;
                    break;
                case UIProvider.DraftType.COMPOSE:
                default:
                    action = COMPOSE;
                    break;
            }
        } else {
            initFromExtras(intent);
        }

        if (action == COMPOSE) {
            mQuotedTextView.setVisibility(View.GONE);
        }
        initRecipients();
        initAttachmentsFromIntent(intent);
        initActionBar(action);
        initFromSpinner(action);
        initChangeListeners();
        setFocus(action);
    }

    private void setFocus(int action) {
        if (action == EDIT_DRAFT) {
            int type = mDraft.draftType;
            switch (type) {
                case UIProvider.DraftType.COMPOSE:
                case UIProvider.DraftType.FORWARD:
                    action = COMPOSE;
                    break;
                case UIProvider.DraftType.REPLY:
                case UIProvider.DraftType.REPLY_ALL:
                default:
                    action = REPLY;
                    break;
            }
        }
        switch (action) {
            case FORWARD:
            case COMPOSE:
                mTo.requestFocus();
                break;
            case REPLY:
            case REPLY_ALL:
            default:
                focusBody();
                break;
        }
    }

    /**
     * Focus the body of the message.
     */
    public void focusBody() {
        mBodyView.requestFocus();
        int length = mBodyView.getText().length();

        int signatureStartPos = getSignatureStartPosition(
                mSignature, mBodyView.getText().toString());
        if (signatureStartPos > -1) {
            // In case the user deleted the newlines...
            mBodyView.setSelection(signatureStartPos);
        } else if (length > 0) {
            // Move cursor to the end.
            mBodyView.setSelection(length);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Update the from spinner as other accounts
        // may now be available.
        if (mFromSpinner != null && mAccount != null) {
            mFromSpinner.asyncInitFromSpinner(mComposeMode, mAccount);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mSendConfirmDialog != null) {
            mSendConfirmDialog.dismiss();
        }
        if (mRecipientErrorDialog != null) {
            mRecipientErrorDialog.dismiss();
        }

        saveIfNeeded();
    }

    @Override
    protected final void onActivityResult(int request, int result, Intent data) {
        mAddingAttachment = false;

        if (result == RESULT_OK && request == RESULT_PICK_ATTACHMENT) {
            addAttachmentAndUpdateView(data);
        }
    }

    @Override
    public final void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);

        // onSaveInstanceState is only called if the user might come back to this activity so it is
        // not an ideal location to save the draft. However, if we have never saved the draft before
        // we have to save it here in order to have an id to save in the bundle.
        saveIfNeededOnOrientationChanged();
    }

    @VisibleForTesting
    void setAccount(Account account) {
        assert(account != null);
        if (!account.equals(mAccount)) {
            mAccount = account;
            mCachedSettings = mAccount.settings;
            appendSignature();
        }
    }

    private void initFromSpinner(int action) {
        if (action == EDIT_DRAFT &&
                mDraft.draftType == UIProvider.DraftType.COMPOSE) {
            action = COMPOSE;
        }
        mFromSpinner.asyncInitFromSpinner(action, mAccount);
        if (mDraft != null) {
            mReplyFromAccount = getReplyFromAccountFromDraft(mAccount, mDraft);
        } else if (mRefMessage != null) {
            mReplyFromAccount = getReplyFromAccountForReply(mAccount, mRefMessage);
        }
        if (mReplyFromAccount == null) {
            mReplyFromAccount = new ReplyFromAccount(mAccount, mAccount.uri, mAccount.name,
                    mAccount.name, true, false);
        }
        mFromSpinner.setCurrentAccount(mReplyFromAccount);
        if (mFromSpinner.getCount() > 1) {
            // If there is only 1 account, just show that account.
            // Otherwise, give the user the ability to choose which account to
            // send mail from / save drafts to.
            mFromStatic.setVisibility(View.GONE);
            mFromStaticText.setText(mAccount.name);
            mFromSpinnerWrapper.setVisibility(View.VISIBLE);
        } else {
            mFromStatic.setVisibility(View.VISIBLE);
            mFromStaticText.setText(mAccount.name);
            mFromSpinnerWrapper.setVisibility(View.GONE);
        }
    }

    private ReplyFromAccount getReplyFromAccountForReply(Account account, Message refMessage) {
        if (refMessage.accountUri != null) {
            // This must be from combined inbox.
            List<ReplyFromAccount> replyFromAccounts = mFromSpinner.getReplyFromAccounts();
            for (ReplyFromAccount from : replyFromAccounts) {
                if (from.account.uri.equals(refMessage.accountUri)) {
                    return from;
                }
            }
            return null;
        } else {
            return getReplyFromAccount(account, refMessage);
        }
    }

    /**
     * Given an account and which email address the message was sent to,
     * return who the message should be sent from.
     * @param account Account in which the message arrived.
     * @param sentTo Email address to which the message was sent.
     * @return the address from which to reply.
     */
    public ReplyFromAccount getReplyFromAccount(Account account, Message refMessage) {
        // First see if we are supposed to use the default address or
        // the address it was sentTo.
        if (false) { //mCachedSettings.forceReplyFromDefault) {
            return getDefaultReplyFromAccount(account);
        } else {
            // If we aren't explicityly told which account to look for, look at
            // all the message recipients and find one that matches
            // a custom from or account.
            List<String> allRecipients = new ArrayList<String>();
            allRecipients.addAll(Arrays.asList(Utils.splitCommaSeparatedString(refMessage.to)));
            allRecipients.addAll(Arrays.asList(Utils.splitCommaSeparatedString(refMessage.cc)));
            return getMatchingRecipient(account, allRecipients);
        }
    }

    /**
     * Compare all the recipients of an email to the current account and all
     * custom addresses associated with that account. Return the match if there
     * is one, or the default account if there isn't.
     */
    protected ReplyFromAccount getMatchingRecipient(Account account, List<String> sentTo) {
        // Tokenize the list and place in a hashmap.
        ReplyFromAccount matchingReplyFrom = null;
        Rfc822Token[] tokens;
        HashSet<String> recipientsMap = new HashSet<String>();
        for (String address : sentTo) {
            tokens = Rfc822Tokenizer.tokenize(address);
            for (int i = 0; i < tokens.length; i++) {
                recipientsMap.add(tokens[i].getAddress());
            }
        }

        int matchingAddressCount = 0;
        List<ReplyFromAccount> customFroms;
        try {
            customFroms = FromAddressSpinner.getAccountSpecificFroms(account);
            if (customFroms != null) {
                for (ReplyFromAccount entry : customFroms) {
                    if (recipientsMap.contains(entry.address)) {
                        matchingReplyFrom = entry;
                        matchingAddressCount++;
                    }
                }
            }
        } catch (JSONException e) {
            LogUtils.wtf(LOG_TAG, "Exception parsing from addresses for account %s",
                    account.name);
        }
        if (matchingAddressCount > 1) {
            matchingReplyFrom = getDefaultReplyFromAccount(account);
        }
        return matchingReplyFrom;
    }

    private ReplyFromAccount getDefaultReplyFromAccount(Account account) {
        List<ReplyFromAccount> replyFromAccounts = mFromSpinner.getReplyFromAccounts();
        for (ReplyFromAccount from : replyFromAccounts) {
            if (from.isDefault) {
                return from;
            }
        }
        return new ReplyFromAccount(account, account.uri, account.name, account.name, true, false);
    }

    private ReplyFromAccount getReplyFromAccountFromDraft(Account account, Message draft) {
        String sender = draft.from;
        ReplyFromAccount replyFromAccount = null;
        List<ReplyFromAccount> replyFromAccounts = mFromSpinner.getReplyFromAccounts();
        if (TextUtils.equals(account.name, sender)) {
            replyFromAccount = new ReplyFromAccount(mAccount, mAccount.uri, mAccount.name,
                    mAccount.name, true, false);
        } else {
            for (ReplyFromAccount fromAccount : replyFromAccounts) {
                if (TextUtils.equals(fromAccount.name, sender)) {
                    replyFromAccount = fromAccount;
                    break;
                }
            }
        }
        return replyFromAccount;
    }

    private void findViews() {
        mCcBccButton = (Button) findViewById(R.id.add_cc_bcc);
        if (mCcBccButton != null) {
            mCcBccButton.setOnClickListener(this);
        }
        mCcBccView = (CcBccView) findViewById(R.id.cc_bcc_wrapper);
        mAttachmentsView = (AttachmentsView)findViewById(R.id.attachments);
        mAttachmentsButton = (ImageView) findViewById(R.id.add_attachment);
        if (mAttachmentsButton != null) {
            mAttachmentsButton.setOnClickListener(this);
        }
        mTo = (RecipientEditTextView) findViewById(R.id.to);
        mCc = (RecipientEditTextView) findViewById(R.id.cc);
        mBcc = (RecipientEditTextView) findViewById(R.id.bcc);
        // TODO: add special chips text change watchers before adding
        // this as a text changed watcher to the to, cc, bcc fields.
        mSubject = (TextView) findViewById(R.id.subject);
        mQuotedTextView = (QuotedTextView) findViewById(R.id.quoted_text_view);
        mQuotedTextView.setRespondInlineListener(this);
        mBodyView = (EditText) findViewById(R.id.body);
        mFromStatic = findViewById(R.id.static_from_content);
        mFromStaticText = (TextView) findViewById(R.id.from_account_name);
        mFromSpinnerWrapper = findViewById(R.id.spinner_from_content);
        mFromSpinner = (FromAddressSpinner) findViewById(R.id.from_picker);
    }

    // Now that the message has been initialized from any existing draft or
    // ref message data, set up listeners for any changes that occur to the
    // message.
    private void initChangeListeners() {
        mSubject.addTextChangedListener(this);
        mBodyView.addTextChangedListener(this);
        mTo.addTextChangedListener(new RecipientTextWatcher(mTo, this));
        mCc.addTextChangedListener(new RecipientTextWatcher(mCc, this));
        mBcc.addTextChangedListener(new RecipientTextWatcher(mBcc, this));
        mFromSpinner.setOnAccountChangedListener(this);
        mAttachmentsView.setAttachmentChangesListener(this);
    }

    private void initActionBar(int action) {
        mComposeMode = action;
        ActionBar actionBar = getActionBar();
        if (action == ComposeActivity.COMPOSE) {
            actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
            actionBar.setTitle(R.string.compose);
        } else {
            actionBar.setTitle(null);
            if (mComposeModeAdapter == null) {
                mComposeModeAdapter = new ComposeModeAdapter(this);
            }
            actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
            actionBar.setListNavigationCallbacks(mComposeModeAdapter, this);
            switch (action) {
                case ComposeActivity.REPLY:
                    actionBar.setSelectedNavigationItem(0);
                    break;
                case ComposeActivity.REPLY_ALL:
                    actionBar.setSelectedNavigationItem(1);
                    break;
                case ComposeActivity.FORWARD:
                    actionBar.setSelectedNavigationItem(2);
                    break;
            }
        }
        actionBar.setDisplayOptions(ActionBar.DISPLAY_HOME_AS_UP | ActionBar.DISPLAY_SHOW_HOME,
                ActionBar.DISPLAY_HOME_AS_UP | ActionBar.DISPLAY_SHOW_HOME);
        actionBar.setHomeButtonEnabled(true);
    }

    private void initFromRefMessage(int action, String recipientAddress) {
        setSubject(mRefMessage, action);
        // Setup recipients
        if (action == FORWARD) {
            mForward = true;
        }
        initRecipientsFromRefMessage(recipientAddress, mRefMessage, action);
        initBodyFromRefMessage(mRefMessage, action);
        if (action == ComposeActivity.FORWARD || mAttachmentsChanged) {
            initAttachments(mRefMessage);
        }
        updateHideOrShowCcBcc();
    }

    private void initFromMessage(Message message) {
        LogUtils.d(LOG_TAG, "Intializing draft from previous draft message");

        mDraft = message;
        mDraftId = message.id;
        mSubject.setText(message.subject);
        mForward = message.draftType == UIProvider.DraftType.FORWARD;
        final List<String> toAddresses = Arrays.asList(message.getToAddresses());
        addToAddresses(toAddresses);
        addCcAddresses(Arrays.asList(message.getCcAddresses()), toAddresses);
        addBccAddresses(Arrays.asList(message.getBccAddresses()));
        if (message.hasAttachments) {
            List<Attachment> attachments = message.getAttachments();
            for (Attachment a : attachments) {
                addAttachmentAndUpdateView(a.uri);
            }
        }

        // Set the body
        if (!TextUtils.isEmpty(message.bodyHtml)) {
            mBodyView.setText(Html.fromHtml(message.bodyHtml));
        } else {
            mBodyView.setText(message.bodyText);
        }

        // TODO: load attachments from the previous message
        // TODO: set the from address spinner to the right from account
        // TODO: initialize quoted text value
    }

    /**
     * Fill all the widgets with the content found in the Intent Extra, if any.
     * Also apply the same style to all widgets. Note: if initFromExtras is
     * called as a result of switching between reply, reply all, and forward per
     * the latest revision of Gmail, and the user has already made changes to
     * attachments on a previous incarnation of the message (as a reply, reply
     * all, or forward), the original attachments from the message will not be
     * re-instantiated. The user's changes will be respected. This follows the
     * web gmail interaction.
     */
    public void initFromExtras(Intent intent) {

        // If we were invoked with a SENDTO intent, the value
        // should take precedence
        final Uri dataUri = intent.getData();
        if (dataUri != null) {
            if (MAIL_TO.equals(dataUri.getScheme())) {
                initFromMailTo(dataUri.toString());
            } else {
                if (!mAccount.composeIntentUri.equals(dataUri)) {
                    String toText = dataUri.getSchemeSpecificPart();
                    if (toText != null) {
                        mTo.setText("");
                        addToAddresses(Arrays.asList(TextUtils.split(toText, ",")));
                    }
                }
            }
        }

        String[] extraStrings = intent.getStringArrayExtra(Intent.EXTRA_EMAIL);
        if (extraStrings != null) {
            addToAddresses(Arrays.asList(extraStrings));
        }
        extraStrings = intent.getStringArrayExtra(Intent.EXTRA_CC);
        if (extraStrings != null) {
            addCcAddresses(Arrays.asList(extraStrings), null);
        }
        extraStrings = intent.getStringArrayExtra(Intent.EXTRA_BCC);
        if (extraStrings != null) {
            addBccAddresses(Arrays.asList(extraStrings));
        }

        String extraString = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        if (extraString != null) {
            mSubject.setText(extraString);
        }

        for (String extra : ALL_EXTRAS) {
            if (intent.hasExtra(extra)) {
                String value = intent.getStringExtra(extra);
                if (EXTRA_TO.equals(extra)) {
                    addToAddresses(Arrays.asList(TextUtils.split(value, ",")));
                } else if (EXTRA_CC.equals(extra)) {
                    addCcAddresses(Arrays.asList(TextUtils.split(value, ",")), null);
                } else if (EXTRA_BCC.equals(extra)) {
                    addBccAddresses(Arrays.asList(TextUtils.split(value, ",")));
                } else if (EXTRA_SUBJECT.equals(extra)) {
                    mSubject.setText(value);
                } else if (EXTRA_BODY.equals(extra)) {
                    setBody(value, true /* with signature */);
                }
            }
        }

        Bundle extras = intent.getExtras();
        if (extras != null) {
            final String action = intent.getAction();
            CharSequence text = extras.getCharSequence(Intent.EXTRA_TEXT);
            if (text != null) {
                setBody(text, true /* with signature */);
            }
        }

        updateHideOrShowCcBcc();
    }

    @VisibleForTesting
    protected String decodeEmailInUri(String s) throws UnsupportedEncodingException {
        // TODO: handle the case where there are spaces in the display name as well as the email
        // such as "Guy with spaces <guy+with+spaces@gmail.com>" as they it could be encoded
        // ambiguously.

        // Since URLDecode.decode changes + into ' ', and + is a valid
        // email character, we need to find/ replace these ourselves before
        // decoding.
        String replacePlus = s.replace("+", "%2B");
        return URLDecoder.decode(replacePlus, UTF8_ENCODING_NAME);
    }

    /**
     * Initialize the compose view from a String representing a mailTo uri.
     * @param mailToString The uri as a string.
     */
    public void initFromMailTo(String mailToString) {
        // We need to disguise this string as a URI in order to parse it
        // TODO:  Remove this hack when http://b/issue?id=1445295 gets fixed
        Uri uri = Uri.parse("foo://" + mailToString);
        int index = mailToString.indexOf("?");
        int length = "mailto".length() + 1;
        String to;
        try {
            // Extract the recipient after mailto:
            if (index == -1) {
                to = decodeEmailInUri(mailToString.substring(length));
            } else {
                to = decodeEmailInUri(mailToString.substring(length, index));
            }
            addToAddresses(Arrays.asList(TextUtils.split(to, ",")));
        } catch (UnsupportedEncodingException e) {
            if (LogUtils.isLoggable(LOG_TAG, LogUtils.VERBOSE)) {
                LogUtils.e(LOG_TAG, "%s while decoding '%s'", e.getMessage(), mailToString);
            } else {
                LogUtils.e(LOG_TAG, e, "Exception  while decoding mailto address");
            }
        }

        List<String> cc = uri.getQueryParameters("cc");
        addCcAddresses(Arrays.asList(cc.toArray(new String[cc.size()])), null);

        List<String> otherTo = uri.getQueryParameters("to");
        addToAddresses(Arrays.asList(otherTo.toArray(new String[otherTo.size()])));

        List<String> bcc = uri.getQueryParameters("bcc");
        addBccAddresses(Arrays.asList(bcc.toArray(new String[bcc.size()])));

        List<String> subject = uri.getQueryParameters("subject");
        if (subject.size() > 0) {
            try {
                mSubject.setText(URLDecoder.decode(subject.get(0), UTF8_ENCODING_NAME));
            } catch (UnsupportedEncodingException e) {
                LogUtils.e(LOG_TAG, "%s while decoding subject '%s'",
                        e.getMessage(), subject);
            }
        }

        List<String> body = uri.getQueryParameters("body");
        if (body.size() > 0) {
            try {
                setBody(URLDecoder.decode(body.get(0), UTF8_ENCODING_NAME),
                        true /* with signature */);
            } catch (UnsupportedEncodingException e) {
                LogUtils.e(LOG_TAG, "%s while decoding body '%s'", e.getMessage(), body);
            }
        }

        updateHideOrShowCcBcc();
    }

    private void initAttachments(Message refMessage) {
        mAttachmentsView.addAttachments(mAccount, refMessage);
    }

    private void initAttachmentsFromIntent(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            extras = Bundle.EMPTY;
        }
        final String action = intent.getAction();
        if (!mAttachmentsChanged) {
            long totalSize = 0;
            if (extras.containsKey(EXTRA_ATTACHMENTS)) {
                String[] uris = (String[]) extras.getSerializable(EXTRA_ATTACHMENTS);
                for (String uriString : uris) {
                    final Uri uri = Uri.parse(uriString);
                    long size = 0;
                    try {
                        size =  mAttachmentsView.addAttachment(mAccount, uri, false /* doSave */,
                                true /* local file */);
                    } catch (AttachmentFailureException e) {
                        // A toast has already been shown to the user,
                        // just break out of the loop.
                        LogUtils.e(LOG_TAG, e, "Error adding attachment");
                    }
                    totalSize += size;
                }
            }
            if (Intent.ACTION_SEND.equals(action) && extras.containsKey(Intent.EXTRA_STREAM)) {
                final Uri uri = (Uri) extras.getParcelable(Intent.EXTRA_STREAM);
                long size = 0;
                try {
                    size =  mAttachmentsView.addAttachment(mAccount, uri, false /* doSave */,
                            true /* local file */);
                } catch (AttachmentFailureException e) {
                    // A toast has already been shown to the user, so just
                    // exit.
                    LogUtils.e(LOG_TAG, e, "Error adding attachment");
                }
                totalSize += size;
            }

            if (Intent.ACTION_SEND_MULTIPLE.equals(action)
                    && extras.containsKey(Intent.EXTRA_STREAM)) {
                ArrayList<Parcelable> uris = extras.getParcelableArrayList(Intent.EXTRA_STREAM);
                for (Parcelable uri : uris) {
                    long size = 0;
                    try {
                        size = mAttachmentsView.addAttachment(mAccount, (Uri) uri,
                                false /* doSave */, true /* local file */);
                    } catch (AttachmentFailureException e) {
                        // A toast has already been shown to the user,
                        // just break out of the loop.
                        LogUtils.e(LOG_TAG, e, "Error adding attachment");
                    }
                    totalSize += size;
                }
            }

            if (totalSize > 0) {
                mAttachmentsChanged = true;
                updateSaveUi();
            }
        }
    }


    private void initBodyFromRefMessage(Message refMessage, int action) {
        if (action == REPLY || action == REPLY_ALL || action == FORWARD) {
            mQuotedTextView.setQuotedText(action, refMessage, action != FORWARD);
        }
    }

    private void updateHideOrShowCcBcc() {
        // Its possible there is a menu item OR a button.
        boolean ccVisible = !TextUtils.isEmpty(mCc.getText());
        boolean bccVisible = !TextUtils.isEmpty(mBcc.getText());
        if (ccVisible || bccVisible) {
            mCcBccView.show(false, ccVisible, bccVisible);
        }
        if (mCcBccButton != null) {
            if (!mCc.isShown() || !mBcc.isShown()) {
                mCcBccButton.setVisibility(View.VISIBLE);
                mCcBccButton.setText(getString(!mCc.isShown() ? R.string.add_cc_label
                        : R.string.add_bcc_label));
            } else {
                mCcBccButton.setVisibility(View.GONE);
            }
        }
    }

    /**
     * Add attachment and update the compose area appropriately.
     * @param data
     */
    public void addAttachmentAndUpdateView(Intent data) {
        addAttachmentAndUpdateView(data != null ? data.getData() : (Uri) null);
    }

    public void addAttachmentAndUpdateView(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            long size =  mAttachmentsView.addAttachment(mAccount, uri,
                    false /* doSave */,
                    true /* local file */);
            if (size > 0) {
                mAttachmentsChanged = true;
                updateSaveUi();
            }
        } catch (AttachmentFailureException e) {
            // A toast has already been shown to the user, no need to do
            // anything.
            LogUtils.e(LOG_TAG, e, "Error adding attachment");
        }
    }

    void initRecipientsFromRefMessage(String recipientAddress, Message refMessage,
            int action) {
        // Don't populate the address if this is a forward.
        if (action == ComposeActivity.FORWARD) {
            return;
        }
        initReplyRecipients(mAccount.name, refMessage, action);
    }

    @VisibleForTesting
    void initReplyRecipients(String account, Message refMessage, int action) {
        // This is the email address of the current user, i.e. the one composing
        // the reply.
        final String accountEmail = Address.getEmailAddress(account).getAddress();
        String fromAddress = refMessage.from;
        String[] sentToAddresses = Utils.splitCommaSeparatedString(refMessage.to);
        String replytoAddress = refMessage.replyTo;
        final Collection<String> toAddresses;

        // If this is a reply, the Cc list is empty. If this is a reply-all, the
        // Cc list is the union of the To and Cc recipients of the original
        // message, excluding the current user's email address and any addresses
        // already on the To list.
        if (action == ComposeActivity.REPLY) {
            toAddresses = initToRecipients(account, accountEmail, fromAddress, replytoAddress,
                    new String[0]);
            addToAddresses(toAddresses);
        } else if (action == ComposeActivity.REPLY_ALL) {
            final Set<String> ccAddresses = Sets.newHashSet();
            toAddresses = initToRecipients(account, accountEmail, fromAddress, replytoAddress,
                    new String[0]);
            addToAddresses(toAddresses);
            addRecipients(accountEmail, ccAddresses, sentToAddresses);
            addRecipients(accountEmail, ccAddresses,
                    Utils.splitCommaSeparatedString(refMessage.cc));
            addCcAddresses(ccAddresses, toAddresses);
        }
    }

    private void addToAddresses(Collection<String> addresses) {
        addAddressesToList(addresses, mTo);
    }

    private void addCcAddresses(Collection<String> addresses, Collection<String> toAddresses) {
        addCcAddressesToList(tokenizeAddressList(addresses),
                toAddresses != null ? tokenizeAddressList(toAddresses) : null, mCc);
    }

    private void addBccAddresses(Collection<String> addresses) {
        addAddressesToList(addresses, mBcc);
    }

    @VisibleForTesting
    protected void addCcAddressesToList(List<Rfc822Token[]> addresses,
            List<Rfc822Token[]> compareToList, RecipientEditTextView list) {
        String address;

        if (compareToList == null) {
            for (Rfc822Token[] tokens : addresses) {
                for (int i = 0; i < tokens.length; i++) {
                    address = tokens[i].toString();
                    list.append(address + END_TOKEN);
                }
            }
        } else {
            HashSet<String> compareTo = convertToHashSet(compareToList);
            for (Rfc822Token[] tokens : addresses) {
                for (int i = 0; i < tokens.length; i++) {
                    address = tokens[i].toString();
                    // Check if this is a duplicate:
                    if (!compareTo.contains(tokens[i].getAddress())) {
                        // Get the address here
                        list.append(address + END_TOKEN);
                    }
                }
            }
        }
    }

    private HashSet<String> convertToHashSet(List<Rfc822Token[]> list) {
        HashSet<String> hash = new HashSet<String>();
        for (Rfc822Token[] tokens : list) {
            for (int i = 0; i < tokens.length; i++) {
                hash.add(tokens[i].getAddress());
            }
        }
        return hash;
    }

    protected List<Rfc822Token[]> tokenizeAddressList(Collection<String> addresses) {
        @VisibleForTesting
        List<Rfc822Token[]> tokenized = new ArrayList<Rfc822Token[]>();

        for (String address: addresses) {
            tokenized.add(Rfc822Tokenizer.tokenize(address));
        }
        return tokenized;
    }

    @VisibleForTesting
    void addAddressesToList(Collection<String> addresses, RecipientEditTextView list) {
        for (String address : addresses) {
            addAddressToList(address, list);
        }
    }

    private void addAddressToList(String address, RecipientEditTextView list) {
        if (address == null || list == null)
            return;

        Rfc822Token[] tokens = Rfc822Tokenizer.tokenize(address);

        for (int i = 0; i < tokens.length; i++) {
            list.append(tokens[i] + END_TOKEN);
        }
    }

    @VisibleForTesting
    protected Collection<String> initToRecipients(String account, String accountEmail,
            String senderAddress, String replyToAddress, String[] inToAddresses) {
        // The To recipient is the reply-to address specified in the original
        // message, unless it is:
        // the current user OR a custom from of the current user, in which case
        // it's the To recipient list of the original message.
        // OR missing, in which case use the sender of the original message
        Set<String> toAddresses = Sets.newHashSet();
        if (!TextUtils.isEmpty(replyToAddress)) {
            toAddresses.add(replyToAddress);
        } else {
            toAddresses.add(senderAddress);
        }
        return toAddresses;
    }

    private static void addRecipients(String account, Set<String> recipients, String[] addresses) {
        for (String email : addresses) {
            // Do not add this account, or any of the custom froms, to the list
            // of recipients.
            final String recipientAddress = Address.getEmailAddress(email).getAddress();
            if (!account.equalsIgnoreCase(recipientAddress)) {
                recipients.add(email.replace("\"\"", ""));
            }
        }
    }

    private void setSubject(Message refMessage, int action) {
        String subject = refMessage.subject;
        String prefix;
        String correctedSubject = null;
        if (action == ComposeActivity.COMPOSE) {
            prefix = "";
        } else if (action == ComposeActivity.FORWARD) {
            prefix = getString(R.string.forward_subject_label);
        } else {
            prefix = getString(R.string.reply_subject_label);
        }

        // Don't duplicate the prefix
        if (subject.toLowerCase().startsWith(prefix.toLowerCase())) {
            correctedSubject = subject;
        } else {
            correctedSubject = String
                    .format(getString(R.string.formatted_subject), prefix, subject);
        }
        mSubject.setText(correctedSubject);
    }

    private void initRecipients() {
        setupRecipients(mTo);
        setupRecipients(mCc);
        setupRecipients(mBcc);
    }

    private void setupRecipients(RecipientEditTextView view) {
        view.setAdapter(new RecipientAdapter(this, mAccount));
        view.setTokenizer(new Rfc822Tokenizer());
        if (mValidator == null) {
            final String accountName = mAccount.name;
            int offset = accountName.indexOf("@") + 1;
            String account = accountName;
            if (offset > -1) {
                account = account.substring(accountName.indexOf("@") + 1);
            }
            mValidator = new Rfc822Validator(account);
        }
        view.setValidator(mValidator);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        switch (id) {
            case R.id.add_cc_bcc:
                // Verify that cc/ bcc aren't showing.
                // Animate in cc/bcc.
                showCcBccViews();
                break;
            case R.id.add_attachment:
                doAttach();
                break;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.compose_menu, menu);
        mSave = menu.findItem(R.id.save);
        mSend = menu.findItem(R.id.send);
        MenuItem helpItem = menu.findItem(R.id.help_info_menu_item);
        MenuItem sendFeedbackItem = menu.findItem(R.id.feedback_menu_item);
        if (helpItem != null) {
            helpItem.setVisible(mAccount != null
                    && mAccount.supportsCapability(AccountCapabilities.HELP_CONTENT));
        }
        if (sendFeedbackItem != null) {
            sendFeedbackItem.setVisible(mAccount != null
                    && mAccount.supportsCapability(AccountCapabilities.SEND_FEEDBACK));
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem ccBcc = menu.findItem(R.id.add_cc_bcc);
        if (ccBcc != null && mCc != null) {
            // Its possible there is a menu item OR a button.
            boolean ccFieldVisible = mCc.isShown();
            boolean bccFieldVisible = mBcc.isShown();
            if (!ccFieldVisible || !bccFieldVisible) {
                ccBcc.setVisible(true);
                ccBcc.setTitle(getString(!ccFieldVisible ? R.string.add_cc_label
                        : R.string.add_bcc_label));
            } else {
                ccBcc.setVisible(false);
            }
        }
        if (mSave != null) {
            mSave.setEnabled(shouldSave());
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        boolean handled = true;
        switch (id) {
            case R.id.add_attachment:
                doAttach();
                break;
            case R.id.add_cc_bcc:
                showCcBccViews();
                break;
            case R.id.save:
                doSave(true, false);
                break;
            case R.id.send:
                doSend();
                break;
            case R.id.discard:
                doDiscard();
                break;
            case R.id.settings:
                Utils.showSettings(this, mAccount);
                break;
            case android.R.id.home:
                finish();
                break;
            case R.id.help_info_menu_item:
                // TODO: enable context sensitive help
                Utils.showHelp(this, mAccount, null);
                break;
            case R.id.feedback_menu_item:
                Utils.sendFeedback(this, mAccount);
                break;
            default:
                handled = false;
                break;
        }
        return !handled ? super.onOptionsItemSelected(item) : handled;
    }

    private void doSend() {
        sendOrSaveWithSanityChecks(false, true, false);
    }

    private void doSave(boolean showToast, boolean resetIME) {
        sendOrSaveWithSanityChecks(true, showToast, false);
        if (resetIME) {
            // Clear the IME composing suggestions from the body.
            BaseInputConnection.removeComposingSpans(mBodyView.getEditableText());
        }
    }

    /*package*/ interface SendOrSaveCallback {
        public void initializeSendOrSave(SendOrSaveTask sendOrSaveTask);
        public void notifyMessageIdAllocated(SendOrSaveMessage sendOrSaveMessage, Message message);
        public Message getMessage();
        public void sendOrSaveFinished(SendOrSaveTask sendOrSaveTask, boolean success);
    }

    /*package*/ static class SendOrSaveTask implements Runnable {
        private final Context mContext;
        private final SendOrSaveCallback mSendOrSaveCallback;
        @VisibleForTesting
        final SendOrSaveMessage mSendOrSaveMessage;

        public SendOrSaveTask(Context context, SendOrSaveMessage message,
                SendOrSaveCallback callback) {
            mContext = context;
            mSendOrSaveCallback = callback;
            mSendOrSaveMessage = message;
        }

        @Override
        public void run() {
            final SendOrSaveMessage sendOrSaveMessage = mSendOrSaveMessage;

            final ReplyFromAccount selectedAccount = sendOrSaveMessage.mAccount;
            Message message = mSendOrSaveCallback.getMessage();
            long messageId = message != null ? message.id : UIProvider.INVALID_MESSAGE_ID;
            // If a previous draft has been saved, in an account that is different
            // than what the user wants to send from, remove the old draft, and treat this
            // as a new message
            if (!selectedAccount.equals(sendOrSaveMessage.mAccount)) {
                if (messageId != UIProvider.INVALID_MESSAGE_ID) {
                    ContentResolver resolver = mContext.getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(BaseColumns._ID, messageId);
                    if (selectedAccount.account.expungeMessageUri != null) {
                        resolver.update(selectedAccount.account.expungeMessageUri, values, null,
                                null);
                    } else {
                        // TODO(mindyp) delete the conversation.
                    }
                    // reset messageId to 0, so a new message will be created
                    messageId = UIProvider.INVALID_MESSAGE_ID;
                }
            }

            final long messageIdToSave = messageId;
            if (messageIdToSave != UIProvider.INVALID_MESSAGE_ID) {
                sendOrSaveMessage.mValues.put(BaseColumns._ID, messageIdToSave);
                mContext.getContentResolver().update(
                        Uri.parse(sendOrSaveMessage.mSave ? message.saveUri : message.sendUri),
                        sendOrSaveMessage.mValues, null, null);
            } else {
                ContentResolver resolver = mContext.getContentResolver();
                Uri messageUri = resolver
                        .insert(sendOrSaveMessage.mSave ? selectedAccount.account.saveDraftUri
                                : selectedAccount.account.sendMessageUri,
                                sendOrSaveMessage.mValues);
                if (sendOrSaveMessage.mSave && messageUri != null) {
                    Cursor messageCursor = resolver.query(messageUri,
                            UIProvider.MESSAGE_PROJECTION, null, null, null);
                    if (messageCursor != null) {
                        try {
                            if (messageCursor.moveToFirst()) {
                                // Broadcast notification that a new message has
                                // been allocated
                                mSendOrSaveCallback.notifyMessageIdAllocated(sendOrSaveMessage,
                                        new Message(messageCursor));
                            }
                        } finally {
                            messageCursor.close();
                        }
                    }
                }
            }

            if (!sendOrSaveMessage.mSave) {
                UIProvider.incrementRecipientsTimesContacted(mContext,
                        (String) sendOrSaveMessage.mValues.get(UIProvider.MessageColumns.TO));
                UIProvider.incrementRecipientsTimesContacted(mContext,
                        (String) sendOrSaveMessage.mValues.get(UIProvider.MessageColumns.CC));
                UIProvider.incrementRecipientsTimesContacted(mContext,
                        (String) sendOrSaveMessage.mValues.get(UIProvider.MessageColumns.BCC));
            }
            mSendOrSaveCallback.sendOrSaveFinished(SendOrSaveTask.this, true);
        }
    }

    // Array of the outstanding send or save tasks.  Access is synchronized
    // with the object itself
    /* package for testing */
    ArrayList<SendOrSaveTask> mActiveTasks = Lists.newArrayList();
    private int mRequestId;
    private String mSignature;

    /*package*/ static class SendOrSaveMessage {
        final ReplyFromAccount mAccount;
        final ContentValues mValues;
        final String mRefMessageId;
        final boolean mSave;
        final int mRequestId;

        public SendOrSaveMessage(ReplyFromAccount account, ContentValues values,
                String refMessageId, boolean save) {
            mAccount = account;
            mValues = values;
            mRefMessageId = refMessageId;
            mSave = save;
            mRequestId = mValues.hashCode() ^ hashCode();
        }

        int requestId() {
            return mRequestId;
        }
    }

    /**
     * Get the to recipients.
     */
    public String[] getToAddresses() {
        return getAddressesFromList(mTo);
    }

    /**
     * Get the cc recipients.
     */
    public String[] getCcAddresses() {
        return getAddressesFromList(mCc);
    }

    /**
     * Get the bcc recipients.
     */
    public String[] getBccAddresses() {
        return getAddressesFromList(mBcc);
    }

    public String[] getAddressesFromList(RecipientEditTextView list) {
        if (list == null) {
            return new String[0];
        }
        Rfc822Token[] tokens = Rfc822Tokenizer.tokenize(list.getText());
        int count = tokens.length;
        String[] result = new String[count];
        for (int i = 0; i < count; i++) {
            result[i] = tokens[i].toString();
        }
        return result;
    }

    /**
     * Check for invalid email addresses.
     * @param to String array of email addresses to check.
     * @param wrongEmailsOut Emails addresses that were invalid.
     */
    public void checkInvalidEmails(String[] to, List<String> wrongEmailsOut) {
        for (String email : to) {
            if (!mValidator.isValid(email)) {
                wrongEmailsOut.add(email);
            }
        }
    }

    /**
     * Show an error because the user has entered an invalid recipient.
     * @param message
     */
    public void showRecipientErrorDialog(String message) {
        // Only 1 invalid recipients error dialog should be allowed up at a
        // time.
        if (mRecipientErrorDialog != null) {
            mRecipientErrorDialog.dismiss();
        }
        mRecipientErrorDialog = new AlertDialog.Builder(this).setMessage(message).setTitle(
                R.string.recipient_error_dialog_title)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setCancelable(false)
                .setPositiveButton(
                        R.string.ok, new Dialog.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                // after the user dismisses the recipient error
                                // dialog we want to make sure to refocus the
                                // recipient to field so they can fix the issue
                                // easily
                                if (mTo != null) {
                                    mTo.requestFocus();
                                }
                                mRecipientErrorDialog = null;
                            }
                        }).show();
    }

    /**
     * Update the state of the UI based on whether or not the current draft
     * needs to be saved and the message is not empty.
     */
    public void updateSaveUi() {
        if (mSave != null) {
            mSave.setEnabled((shouldSave() && !isBlank()));
        }
    }

    /**
     * Returns true if we need to save the current draft.
     */
    private boolean shouldSave() {
        synchronized (mDraftLock) {
            // The message should only be saved if:
            // It hasn't been sent AND
            // Some text has been added to the message OR
            // an attachment has been added or removed
            return (mTextChanged || mAttachmentsChanged ||
                    (mReplyFromChanged && !isBlank()));
        }
    }

    /**
     * Check if all fields are blank.
     * @return boolean
     */
    public boolean isBlank() {
        return mSubject.getText().length() == 0
                && (mBodyView.getText().length() == 0 || getSignatureStartPosition(mSignature,
                        mBodyView.getText().toString()) == 0)
                && mTo.length() == 0
                && mCc.length() == 0 && mBcc.length() == 0
                && mAttachmentsView.getAttachments().size() == 0;
    }

    @VisibleForTesting
    protected int getSignatureStartPosition(String signature, String bodyText) {
        int startPos = -1;

        if (TextUtils.isEmpty(signature) || TextUtils.isEmpty(bodyText)) {
            return startPos;
        }

        int bodyLength = bodyText.length();
        int signatureLength = signature.length();
        String printableVersion = convertToPrintableSignature(signature);
        int printableLength = printableVersion.length();

        if (bodyLength >= printableLength
                && bodyText.substring(bodyLength - printableLength)
                .equals(printableVersion)) {
            startPos = bodyLength - printableLength;
        } else if (bodyLength >= signatureLength
                && bodyText.substring(bodyLength - signatureLength)
                .equals(signature)) {
            startPos = bodyLength - signatureLength;
        }
        return startPos;
    }

    /**
     * Allows any changes made by the user to be ignored. Called when the user
     * decides to discard a draft.
     */
    private void discardChanges() {
        mTextChanged = false;
        mAttachmentsChanged = false;
        mReplyFromChanged = false;
    }

    /**
     * @param body
     * @param save
     * @param showToast
     * @return Whether the send or save succeeded.
     */
    protected boolean sendOrSaveWithSanityChecks(final boolean save, final boolean showToast,
            final boolean orientationChanged) {
        String[] to, cc, bcc;
        Editable body = mBodyView.getEditableText();

        if (orientationChanged) {
            to = cc = bcc = new String[0];
        } else {
            to = getToAddresses();
            cc = getCcAddresses();
            bcc = getBccAddresses();
        }

        // Don't let the user send to nobody (but it's okay to save a message
        // with no recipients)
        if (!save && (to.length == 0 && cc.length == 0 && bcc.length == 0)) {
            showRecipientErrorDialog(getString(R.string.recipient_needed));
            return false;
        }

        List<String> wrongEmails = new ArrayList<String>();
        if (!save) {
            checkInvalidEmails(to, wrongEmails);
            checkInvalidEmails(cc, wrongEmails);
            checkInvalidEmails(bcc, wrongEmails);
        }

        // Don't let the user send an email with invalid recipients
        if (wrongEmails.size() > 0) {
            String errorText = String.format(getString(R.string.invalid_recipient),
                    wrongEmails.get(0));
            showRecipientErrorDialog(errorText);
            return false;
        }

        DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                sendOrSave(mBodyView.getEditableText(), save, showToast, orientationChanged);
            }
        };

        // Show a warning before sending only if there are no attachments.
        if (!save) {
            if (mAttachmentsView.getAttachments().isEmpty() && showEmptyTextWarnings()) {
                boolean warnAboutEmptySubject = isSubjectEmpty();
                boolean emptyBody = TextUtils.getTrimmedLength(body) == 0;

                // A warning about an empty body may not be warranted when
                // forwarding mails, since a common use case is to forward
                // quoted text and not append any more text.
                boolean warnAboutEmptyBody = emptyBody && (!mForward || isBodyEmpty());

                // When we bring up a dialog warning the user about a send,
                // assume that they accept sending the message. If they do not,
                // the dialog listener is required to enable sending again.
                if (warnAboutEmptySubject) {
                    showSendConfirmDialog(R.string.confirm_send_message_with_no_subject, listener);
                    return true;
                }

                if (warnAboutEmptyBody) {
                    showSendConfirmDialog(R.string.confirm_send_message_with_no_body, listener);
                    return true;
                }
            }
            // Ask for confirmation to send (if always required)
            if (showSendConfirmation()) {
                showSendConfirmDialog(R.string.confirm_send_message, listener);
                return true;
            }
        }

        sendOrSave(body, save, showToast, false);
        return true;
    }

    /**
     * Returns a boolean indicating whether warnings should be shown for empty
     * subject and body fields
     * 
     * @return True if a warning should be shown for empty text fields
     */
    protected boolean showEmptyTextWarnings() {
        return mAttachmentsView.getAttachments().size() == 0;
    }

    /**
     * Returns a boolean indicating whether the user should confirm each send
     *
     * @return True if a warning should be on each send
     */
    protected boolean showSendConfirmation() {
        return mCachedSettings != null ? mCachedSettings.confirmSend : false;
    }

    private void showSendConfirmDialog(int messageId, DialogInterface.OnClickListener listener) {
        if (mSendConfirmDialog != null) {
            mSendConfirmDialog.dismiss();
            mSendConfirmDialog = null;
        }
        mSendConfirmDialog = new AlertDialog.Builder(this).setMessage(messageId)
                .setTitle(R.string.confirm_send_title)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setPositiveButton(R.string.send, listener)
                .setNegativeButton(R.string.cancel, this).setCancelable(false).show();
    }

    /**
     * Returns whether the ComposeArea believes there is any text in the body of
     * the composition. TODO: When ComposeArea controls the Body as well, add
     * that here.
     */
    public boolean isBodyEmpty() {
        return !mQuotedTextView.isTextIncluded();
    }

    /**
     * Test to see if the subject is empty.
     *
     * @return boolean.
     */
    // TODO: this will likely go away when composeArea.focus() is implemented
    // after all the widget control is moved over.
    public boolean isSubjectEmpty() {
        return TextUtils.getTrimmedLength(mSubject.getText()) == 0;
    }

    /* package */
    static int sendOrSaveInternal(Context context, final ReplyFromAccount replyFromAccount,
            String fromAddress, final Spanned body, final String[] to, final String[] cc,
            final String[] bcc, final String subject, final CharSequence quotedText,
            final List<Attachment> attachments, final Message refMessage,
            SendOrSaveCallback callback, Handler handler, boolean save, int composeMode) {
        ContentValues values = new ContentValues();

        String refMessageId = refMessage != null ? refMessage.uri.toString() : "";

        MessageModification.putToAddresses(values, to);
        MessageModification.putCcAddresses(values, cc);
        MessageModification.putBccAddresses(values, bcc);

        MessageModification.putCustomFromAddress(values, replyFromAccount.address);

        MessageModification.putSubject(values, subject);
        String htmlBody = Html.toHtml(body);
        boolean includeQuotedText = !TextUtils.isEmpty(quotedText);
        StringBuilder fullBody = new StringBuilder(htmlBody);
        if (includeQuotedText) {
            // HTML gets converted to text for now
            final String text = quotedText.toString();
            if (QuotedTextView.containsQuotedText(text)) {
                int pos = QuotedTextView.getQuotedTextOffset(text);
                fullBody.append(text.substring(0, pos));
                MessageModification.putQuoteStartPos(values, fullBody.length());
                MessageModification.putForward(values, composeMode == ComposeActivity.FORWARD);
                MessageModification.putAppendRefMessageContent(values, includeQuotedText);
            } else {
                LogUtils.w(LOG_TAG, "Couldn't find quoted text");
                // This shouldn't happen, but just use what we have,
                // and don't do server-side expansion
                fullBody.append(text);
            }
        }
        int draftType = -1;
        switch (composeMode) {
            case ComposeActivity.COMPOSE:
                draftType = DraftType.COMPOSE;
                break;
            case ComposeActivity.REPLY:
                draftType = DraftType.REPLY;
                break;
            case ComposeActivity.REPLY_ALL:
                draftType = DraftType.REPLY_ALL;
                break;
            case ComposeActivity.FORWARD:
                draftType = DraftType.FORWARD;
                break;
        }
        MessageModification.putDraftType(values, draftType);
        if (refMessage != null) {
            if (!TextUtils.isEmpty(refMessage.bodyHtml)) {
                MessageModification.putBodyHtml(values, fullBody.toString());
            }
            if (!TextUtils.isEmpty(refMessage.bodyText)) {
                MessageModification.putBody(values, Html.fromHtml(fullBody.toString()).toString());
            }
        } else {
            MessageModification.putBodyHtml(values, fullBody.toString());
            MessageModification.putBody(values, Html.fromHtml(fullBody.toString()).toString());
        }
        MessageModification.putAttachments(values, attachments);
        if (!TextUtils.isEmpty(refMessageId)) {
            MessageModification.putRefMessageId(values, refMessageId);
        }

        SendOrSaveMessage sendOrSaveMessage = new SendOrSaveMessage(replyFromAccount,
                values, refMessageId, save);
        SendOrSaveTask sendOrSaveTask = new SendOrSaveTask(context, sendOrSaveMessage, callback);

        callback.initializeSendOrSave(sendOrSaveTask);

        // Do the send/save action on the specified handler to avoid possible
        // ANRs
        handler.post(sendOrSaveTask);

        return sendOrSaveMessage.requestId();
    }

    private void sendOrSave(Spanned body, boolean save, boolean showToast,
            boolean orientationChanged) {
        // Check if user is a monkey. Monkeys can compose and hit send
        // button but are not allowed to send anything off the device.
        if (ActivityManager.isUserAMonkey()) {
            return;
        }

        String[] to, cc, bcc;
        if (orientationChanged) {
            to = cc = bcc = new String[0];
        } else {
            to = getToAddresses();
            cc = getCcAddresses();
            bcc = getBccAddresses();
        }

        SendOrSaveCallback callback = new SendOrSaveCallback() {
            private int mRestoredRequestId;

            public void initializeSendOrSave(SendOrSaveTask sendOrSaveTask) {
                synchronized (mActiveTasks) {
                    int numTasks = mActiveTasks.size();
                    if (numTasks == 0) {
                        // Start service so we won't be killed if this app is
                        // put in the background.
                        startService(new Intent(ComposeActivity.this, EmptyService.class));
                    }

                    mActiveTasks.add(sendOrSaveTask);
                }
                if (sTestSendOrSaveCallback != null) {
                    sTestSendOrSaveCallback.initializeSendOrSave(sendOrSaveTask);
                }
            }

            public void notifyMessageIdAllocated(SendOrSaveMessage sendOrSaveMessage,
                    Message message) {
                synchronized (mDraftLock) {
                    mDraftId = message.id;
                    mDraft = message;
                    if (sRequestMessageIdMap != null) {
                        sRequestMessageIdMap.put(sendOrSaveMessage.requestId(), mDraftId);
                    }
                    // Cache request message map, in case the process is killed
                    saveRequestMap();
                }
                if (sTestSendOrSaveCallback != null) {
                    sTestSendOrSaveCallback.notifyMessageIdAllocated(sendOrSaveMessage, message);
                }
            }

            public Message getMessage() {
                synchronized (mDraftLock) {
                    return mDraft;
                }
            }

            public void sendOrSaveFinished(SendOrSaveTask task, boolean success) {
                if (success) {
                    // Successfully sent or saved so reset change markers
                    discardChanges();
                } else {
                    // A failure happened with saving/sending the draft
                    // TODO(pwestbro): add a better string that should be used
                    // when failing to send or save
                    Toast.makeText(ComposeActivity.this, R.string.send_failed, Toast.LENGTH_SHORT)
                            .show();
                }

                int numTasks;
                synchronized (mActiveTasks) {
                    // Remove the task from the list of active tasks
                    mActiveTasks.remove(task);
                    numTasks = mActiveTasks.size();
                }

                if (numTasks == 0) {
                    // Stop service so we can be killed.
                    stopService(new Intent(ComposeActivity.this, EmptyService.class));
                }
                if (sTestSendOrSaveCallback != null) {
                    sTestSendOrSaveCallback.sendOrSaveFinished(task, success);
                }
            }
        };

        // Get the selected account if the from spinner has been setup.
        ReplyFromAccount selectedAccount = mReplyFromAccount;
        String fromAddress = selectedAccount.name;
        if (selectedAccount == null || fromAddress == null) {
            // We don't have either the selected account or from address,
            // use mAccount.
            selectedAccount = mReplyFromAccount;
            fromAddress = mAccount.name;
        }

        if (mSendSaveTaskHandler == null) {
            HandlerThread handlerThread = new HandlerThread("Send Message Task Thread");
            handlerThread.start();

            mSendSaveTaskHandler = new Handler(handlerThread.getLooper());
        }

        mRequestId = sendOrSaveInternal(this, mReplyFromAccount, fromAddress, body, to, cc,
                bcc, mSubject.getText().toString(), mQuotedTextView.getQuotedTextIfIncluded(),
                mAttachmentsView.getAttachments(), mRefMessage, callback,
                mSendSaveTaskHandler, save, mComposeMode);

        if (mRecipient != null && mRecipient.equals(mAccount.name)) {
            mRecipient = selectedAccount.name;
        }
        setAccount(selectedAccount.account);

        // Don't display the toast if the user is just changing the orientation,
        // but we still need to save the draft to the cursor because this is how we restore
        // the attachments when the configuration change completes.
        if (showToast && (getChangingConfigurations() & ActivityInfo.CONFIG_ORIENTATION) == 0) {
            Toast.makeText(this, save ? R.string.message_saved : R.string.sending_message,
                    Toast.LENGTH_LONG).show();
        }

        // Need to update variables here because the send or save completes
        // asynchronously even though the toast shows right away.
        discardChanges();
        updateSaveUi();

        // If we are sending, finish the activity
        if (!save) {
            finish();
        }
    }

    /**
     * Save the state of the request messageid map. This allows for the Gmail
     * process to be killed, but and still allow for ComposeActivity instances
     * to be recreated correctly.
     */
    private void saveRequestMap() {
        // TODO: store the request map in user preferences.
    }

    public void doAttach() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        if (android.provider.Settings.System.getInt(getContentResolver(),
                UIProvider.getAttachmentTypeSetting(), 0) != 0) {
            i.setType("*/*");
        } else {
            i.setType("image/*");
        }
        mAddingAttachment = true;
        startActivityForResult(Intent.createChooser(i, getText(R.string.select_attachment_type)),
                RESULT_PICK_ATTACHMENT);
    }

    private void showCcBccViews() {
        mCcBccView.show(true, true, true);
        if (mCcBccButton != null) {
            mCcBccButton.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean onNavigationItemSelected(int position, long itemId) {
        int initialComposeMode = mComposeMode;
        if (position == ComposeActivity.REPLY) {
            mComposeMode = ComposeActivity.REPLY;
        } else if (position == ComposeActivity.REPLY_ALL) {
            mComposeMode = ComposeActivity.REPLY_ALL;
        } else if (position == ComposeActivity.FORWARD) {
            mComposeMode = ComposeActivity.FORWARD;
        }
        if (initialComposeMode != mComposeMode) {
            resetMessageForModeChange();
            if (mRefMessage != null) {
                initFromRefMessage(mComposeMode, mAccount.name);
            }
        }
        return true;
    }

    private void resetMessageForModeChange() {
        // When switching between reply, reply all, forward,
        // follow the behavior of webview.
        // The contents of the following fields are cleared
        // so that they can be populated directly from the
        // ref message:
        // 1) Any recipient fields
        // 2) The subject
        mTo.setText("");
        mCc.setText("");
        mBcc.setText("");
        // Any edits to the subject are replaced with the original subject.
        mSubject.setText("");

        // Any changes to the contents of the following fields are kept:
        // 1) Body
        // 2) Attachments
        // If the user made changes to attachments, keep their changes.
        if (!mAttachmentsChanged) {
            mAttachmentsView.deleteAllAttachments();
        }
    }

    private class ComposeModeAdapter extends ArrayAdapter<String> {

        private LayoutInflater mInflater;

        public ComposeModeAdapter(Context context) {
            super(context, R.layout.compose_mode_item, R.id.mode, getResources()
                    .getStringArray(R.array.compose_modes));
        }

        private LayoutInflater getInflater() {
            if (mInflater == null) {
                mInflater = LayoutInflater.from(getContext());
            }
            return mInflater;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getInflater().inflate(R.layout.compose_mode_display_item, null);
            }
            ((TextView) convertView.findViewById(R.id.mode)).setText(getItem(position));
            return super.getView(position, convertView, parent);
        }
    }

    @Override
    public void onRespondInline(String text) {
        appendToBody(text, false);
    }

    /**
     * Append text to the body of the message. If there is no existing body
     * text, just sets the body to text.
     *
     * @param text
     * @param withSignature True to append a signature.
     */
    public void appendToBody(CharSequence text, boolean withSignature) {
        Editable bodyText = mBodyView.getEditableText();
        if (bodyText != null && bodyText.length() > 0) {
            bodyText.append(text);
        } else {
            setBody(text, withSignature);
        }
    }

    /**
     * Set the body of the message.
     *
     * @param text
     * @param withSignature True to append a signature.
     */
    public void setBody(CharSequence text, boolean withSignature) {
        mBodyView.setText(text);
        if (withSignature) {
            appendSignature();
        }
    }

    private void appendSignature() {
        String newSignature = mCachedSettings != null ? mCachedSettings.signature : null;
        boolean hasFocus = mBodyView.hasFocus();
        if (!TextUtils.equals(newSignature, mSignature)) {
            mSignature = newSignature;
            if (!TextUtils.isEmpty(mSignature)
                    && getSignatureStartPosition(mSignature,
                            mBodyView.getText().toString()) < 0) {
                // Appending a signature does not count as changing text.
                mBodyView.removeTextChangedListener(this);
                mBodyView.append(convertToPrintableSignature(mSignature));
                mBodyView.addTextChangedListener(this);
            }
            if (hasFocus) {
                focusBody();
            }
        }
    }

    private String convertToPrintableSignature(String signature) {
        String signatureResource = getResources().getString(R.string.signature);
        if (signature == null) {
            signature = "";
        }
        return String.format(signatureResource, signature);
    }

    @Override
    public void onAccountChanged() {
        mReplyFromAccount = mFromSpinner.getCurrentAccount();
        if (!mAccount.equals(mReplyFromAccount.account)) {
            setAccount(mReplyFromAccount.account);

            // TODO: handle discarding attachments when switching accounts.
            // Only enable save for this draft if there is any other content
            // in the message.
            if (!isBlank()) {
                enableSave(true);
            }
            mReplyFromChanged = true;
            initRecipients();
        }
    }

    public void enableSave(boolean enabled) {
        if (mSave != null) {
            mSave.setEnabled(enabled);
        }
    }

    public void enableSend(boolean enabled) {
        if (mSend != null) {
            mSend.setEnabled(enabled);
        }
    }

    /**
     * Handles button clicks from any error dialogs dealing with sending
     * a message.
     */
    @Override
    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case DialogInterface.BUTTON_POSITIVE: {
                doDiscardWithoutConfirmation(true /* show toast */ );
                break;
            }
            case DialogInterface.BUTTON_NEGATIVE: {
                // If the user cancels the send, re-enable the send button.
                enableSend(true);
                break;
            }
        }

    }

    private void doDiscard() {
        new AlertDialog.Builder(this).setMessage(R.string.confirm_discard_text)
                .setPositiveButton(R.string.ok, this)
                .setNegativeButton(R.string.cancel, null)
                .create().show();
    }
    /**
     * Effectively discard the current message.
     *
     * This method is either invoked from the menu or from the dialog
     * once the user has confirmed that they want to discard the message.
     * @param showToast show "Message discarded" toast if true
     */
    private void doDiscardWithoutConfirmation(boolean showToast) {
        synchronized (mDraftLock) {
            if (mDraftId != UIProvider.INVALID_MESSAGE_ID) {
                ContentValues values = new ContentValues();
                values.put(BaseColumns._ID, mDraftId);
                if (mAccount.expungeMessageUri != null) {
                    getContentResolver().update(mAccount.expungeMessageUri, values, null, null);
                } else {
                    // TODO(mindyp): call delete on this conversation instead.
                }
                // This is not strictly necessary (since we should not try to
                // save the draft after calling this) but it ensures that if we
                // do save again for some reason we make a new draft rather than
                // trying to resave an expunged draft.
                mDraftId = UIProvider.INVALID_MESSAGE_ID;
            }
        }

        if (showToast) {
            // Display a toast to let the user know
            Toast.makeText(this, R.string.message_discarded, Toast.LENGTH_SHORT).show();
        }

        // This prevents the draft from being saved in onPause().
        discardChanges();
        finish();
    }

    private void saveIfNeeded() {
        if (mAccount == null) {
            // We have not chosen an account yet so there's no way that we can save. This is ok,
            // though, since we are saving our state before AccountsActivity is activated. Thus, the
            // user has not interacted with us yet and there is no real state to save.
            return;
        }

        if (shouldSave()) {
            doSave(!mAddingAttachment /* show toast */, true /* reset IME */);
        }
    }

    private void saveIfNeededOnOrientationChanged() {
        if (mAccount == null) {
            // We have not chosen an account yet so there's no way that we can save. This is ok,
            // though, since we are saving our state before AccountsActivity is activated. Thus, the
            // user has not interacted with us yet and there is no real state to save.
            return;
        }

        if (shouldSave()) {
            doSaveOrientationChanged(!mAddingAttachment /* show toast */, true /* reset IME */);
        }
    }

    /**
     * Save a draft if a draft already exists or the message is not empty.
     */
    public void doSaveOrientationChanged(boolean showToast, boolean resetIME) {
        saveOnOrientationChanged();
        if (resetIME) {
            // Clear the IME composing suggestions from the body.
            BaseInputConnection.removeComposingSpans(mBodyView.getEditableText());
        }
    }

    protected boolean saveOnOrientationChanged() {
        return sendOrSaveWithSanityChecks(true, false, true);
    }

    @Override
    public void onAttachmentDeleted() {
        mAttachmentsChanged = true;
        updateSaveUi();
    }


    /**
     * This is called any time one of our text fields changes.
     */
    public void afterTextChanged(Editable s) {
        mTextChanged = true;
        updateSaveUi();
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        // Do nothing.
    }

    public void onTextChanged(CharSequence s, int start, int before, int count) {
        // Do nothing.
    }


    // There is a big difference between the text associated with an address changing
    // to add the display name or to format properly and a recipient being added or deleted.
    // Make sure we only notify of changes when a recipient has been added or deleted.
    private class RecipientTextWatcher implements TextWatcher {
        private HashMap<String, Integer> mContent = new HashMap<String, Integer>();

        private RecipientEditTextView mView;

        private TextWatcher mListener;

        public RecipientTextWatcher(RecipientEditTextView view, TextWatcher listener) {
            mView = view;
            mListener = listener;
        }

        @Override
        public void afterTextChanged(Editable s) {
            if (hasChanged()) {
                mListener.afterTextChanged(s);
            }
        }

        private boolean hasChanged() {
            String[] currRecips = tokenizeRecips(getAddressesFromList(mView));
            int totalCount = currRecips.length;
            int totalPrevCount = 0;
            for (Entry<String, Integer> entry : mContent.entrySet()) {
                totalPrevCount += entry.getValue();
            }
            if (totalCount != totalPrevCount) {
                return true;
            }

            for (String recip : currRecips) {
                if (!mContent.containsKey(recip)) {
                    return true;
                } else {
                    int count = mContent.get(recip) - 1;
                    if (count < 0) {
                        return true;
                    } else {
                        mContent.put(recip, count);
                    }
                }
            }
            return false;
        }

        private String[] tokenizeRecips(String[] recips) {
            // Tokenize them all and put them in the list.
            String[] recipAddresses = new String[recips.length];
            for (int i = 0; i < recips.length; i++) {
                recipAddresses[i] = Rfc822Tokenizer.tokenize(recips[i])[0].getAddress();
            }
            return recipAddresses;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            String[] recips = tokenizeRecips(getAddressesFromList(mView));
            for (String recip : recips) {
                if (!mContent.containsKey(recip)) {
                    mContent.put(recip, 1);
                } else {
                    mContent.put(recip, (mContent.get(recip)) + 1);
                }
            }
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            // Do nothing.
        }
    }
}