/*
 * Copyright (C) 2015 The MaxiCM
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.maxicm.setupwizard.setup;

import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ContentQueryMap;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import com.maxicm.setupwizard.R;
import com.maxicm.setupwizard.SetupWizardApp;
import com.maxicm.setupwizard.ui.LoadingFragment;
import com.maxicm.setupwizard.util.SetupWizardUtils;

import java.io.IOException;
import java.util.Observable;
import java.util.Observer;

public class GmsAccountPage extends SetupPage {

    public static final String TAG = "GmsAccountPage";

    public static final String ACTION_RESTORE = "com.google.android.setupwizard.RESTORE";
    private static final String RESTORE_WIZARD_SCRIPT =
            "android.resource://com.google.android.setupwizard/xml/wizard_script";

    private ContentQueryMap mContentQueryMap;
    private Observer mSettingsObserver;

    private boolean mBackupEnabled = false;

    private Fragment mFragment;

    public GmsAccountPage(final Context context, SetupDataCallbacks callbacks) {
        super(context, callbacks);
        final ContentResolver res = context.getContentResolver();
        mBackupEnabled = Settings.Secure.getInt(res,
                Settings.Secure.BACKUP_ENABLED, 0) == 1;
        mSettingsObserver = new Observer() {
            public void update(Observable o, Object arg) {
                mBackupEnabled = (Settings.Secure.getInt(res,
                        Settings.Secure.BACKUP_AUTO_RESTORE, 0) == 1) ||
                        (Settings.Secure.getInt(res,
                                Settings.Secure.BACKUP_ENABLED, 0) == 1);
            }
        };
        Cursor settingsCursor = res.query(Settings.Secure.CONTENT_URI, null,
                "(" + Settings.System.NAME + "=? OR " + Settings.System.NAME + "=?)",
                new String[]{Settings.Secure.BACKUP_AUTO_RESTORE, Settings.Secure.BACKUP_ENABLED},
                null);
        mContentQueryMap = new ContentQueryMap(settingsCursor, Settings.System.NAME, true, null);
        mContentQueryMap.addObserver(mSettingsObserver);
    }

    @Override
    public Fragment getFragment(FragmentManager fragmentManager, int action) {
        mFragment = fragmentManager.findFragmentByTag(getKey());
        if (mFragment == null) {
            Bundle args = new Bundle();
            args.putString(Page.KEY_PAGE_ARGUMENT, getKey());
            args.putInt(Page.KEY_PAGE_ACTION, action);
            mFragment = new LoadingFragment();
            mFragment.setArguments(args);
        }
        return mFragment;
    }

    @Override
    public String getKey() {
        return TAG;
    }

    @Override
    public int getTitleResId() {
        return R.string.loading;
    }

    @Override
    public int getNextButtonTitleResId() {
        return R.string.skip;
    }

    @Override
    public void doLoadAction(FragmentManager fragmentManager, int action) {
        if (action == Page.ACTION_PREVIOUS) {
            getCallbacks().onPreviousPage();
        } else {
            super.doLoadAction(fragmentManager, action);
            launchGmsAccountSetup();
        }
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SetupWizardApp.REQUEST_CODE_SETUP_GMS) {
            if (!mBackupEnabled && SetupWizardUtils.isOwner() && resultCode == Activity.RESULT_OK) {
                launchGmsRestorePage();
            } else {
                handleResult(resultCode);
            }
        } else if (requestCode == SetupWizardApp.REQUEST_CODE_RESTORE_GMS) {
            handleResult(resultCode);
            setHidden(true);
        }
        return true;
    }

    @Override
    public void onFinishSetup() {
        try {
            if (mContentQueryMap != null) {
                mContentQueryMap.close();
            }
        } catch (Exception e) {
            Log.wtf(TAG, e.toString());
        }
    }

    private void handleResult(int resultCode) {
        if (resultCode == Activity.RESULT_CANCELED) {
            getCallbacks().onPreviousPage();
        }  else {
            if (SetupWizardUtils.accountExists(mContext, SetupWizardApp.ACCOUNT_TYPE_GMS)) {
                setHidden(true);
            }
            getCallbacks().onNextPage();
        }
    }

    private void launchGmsRestorePage() {
        try {
            // GMS can disable this after logging in sometimes
            if (SetupWizardUtils.enableGMSSetupWizard(mContext)) {
                Intent intent = new Intent(ACTION_RESTORE);
                intent.putExtra(SetupWizardApp.EXTRA_ALLOW_SKIP, true);
                intent.putExtra(SetupWizardApp.EXTRA_USE_IMMERSIVE, true);
                intent.putExtra(SetupWizardApp.EXTRA_FIRST_RUN, true);
                intent.putExtra(SetupWizardApp.EXTRA_THEME, SetupWizardApp.EXTRA_MATERIAL_LIGHT);
                // XXX: Fool G's setup wizard into thinking it is their setup wizard.
                // This is necessary to get the material theme on the restore page.
                intent.putExtra("scriptUri", RESTORE_WIZARD_SCRIPT);
                ActivityOptions options =
                        ActivityOptions.makeCustomAnimation(mContext,
                                android.R.anim.fade_in,
                                android.R.anim.fade_out);
                mFragment.startActivityForResult(
                        intent,
                        SetupWizardApp.REQUEST_CODE_RESTORE_GMS, options.toBundle());
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
            // XXX: In open source, we don't know what gms version a user has.
            // Bail if the restore activity is not found.
        }
        getCallbacks().onNextPage();
    }

    private void launchGmsAccountSetup() {
        Bundle bundle = new Bundle();
        bundle.putBoolean(SetupWizardApp.EXTRA_FIRST_RUN, true);
        bundle.putBoolean(SetupWizardApp.EXTRA_ALLOW_SKIP, true);
        bundle.putBoolean(SetupWizardApp.EXTRA_USE_IMMERSIVE, true);
        AccountManager
                .get(mContext).addAccount(SetupWizardApp.ACCOUNT_TYPE_GMS, null, null,
                bundle, null, new AccountManagerCallback<Bundle>() {
                    @Override
                    public void run(AccountManagerFuture<Bundle> future) {
                        boolean error = false;
                        try {
                            Bundle result = future.getResult();
                            Intent intent = result
                                    .getParcelable(AccountManager.KEY_INTENT);
                            ActivityOptions options =
                                    ActivityOptions.makeCustomAnimation(mContext,
                                            android.R.anim.fade_in,
                                            android.R.anim.fade_out);
                            mFragment.startActivityForResult(intent,
                                    SetupWizardApp.REQUEST_CODE_SETUP_GMS, options.toBundle());
                        } catch (OperationCanceledException e) {
                            error = true;
                        } catch (IOException e) {
                            error = true;
                        } catch (AuthenticatorException e) {
                            Log.e(TAG, "Error launching gms account", e);
                            error = true;
                        } finally {
                            if (error && getCallbacks().
                                    isCurrentPage(GmsAccountPage.this)) {
                                getCallbacks().onNextPage();
                            }
                        }
                    }
                }, null);
    }
}
