/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.settings.search2;

import android.app.ActionBar;
import android.app.Activity;
import android.app.LoaderManager;
import android.content.Context;
import android.content.Loader;
import android.os.Bundle;
import android.support.annotation.VisibleForTesting;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.SearchView;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.Utils;
import com.android.settings.core.InstrumentedFragment;
import com.android.settings.core.instrumentation.MetricsFeatureProvider;
import com.android.settings.overlay.FeatureFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class SearchFragment extends InstrumentedFragment implements
        SearchView.OnQueryTextListener, LoaderManager.LoaderCallbacks<List<? extends SearchResult>>
{
    private static final String TAG = "SearchFragment";

    @VisibleForTesting
    static final int SEARCH_TAG = "SearchViewTag".hashCode();

    // State values
    private static final String STATE_QUERY = "state_query";
    private static final String STATE_NEVER_ENTERED_QUERY = "state_never_entered_query";
    private static final String STATE_RESULT_CLICK_COUNT = "state_result_click_count";

    // Loader IDs
    private static final int LOADER_ID_RECENTS = 0;
    private static final int LOADER_ID_DATABASE = 1;
    private static final int LOADER_ID_INSTALLED_APPS = 2;

    private static final int NUM_QUERY_LOADERS = 2;

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    AtomicInteger mUnfinishedLoadersCount = new AtomicInteger(NUM_QUERY_LOADERS);;

    // Logging
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    static final String RESULT_CLICK_COUNT = "settings_search_result_click_count";

    @VisibleForTesting
    String mQuery;

    private final SaveQueryRecorderCallback mSaveQueryRecorderCallback =
            new SaveQueryRecorderCallback();

    private boolean mNeverEnteredQuery = true;
    private int mResultClickCount;
    private MetricsFeatureProvider mMetricsFeatureProvider;

    @VisibleForTesting (otherwise = VisibleForTesting.PRIVATE)
    SearchFeatureProvider mSearchFeatureProvider;

    private SearchResultsAdapter mSearchAdapter;

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    RecyclerView mResultsRecyclerView;
    private SearchView mSearchView;
    private LinearLayout mNoResultsView;

    @VisibleForTesting
    final RecyclerView.OnScrollListener mScrollListener =
            new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            if (dy != 0) {
                hideKeyboard();
            }
        }
    };

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.DASHBOARD_SEARCH_RESULTS;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mSearchFeatureProvider = FeatureFactory.getFactory(context).getSearchFeatureProvider();
        mMetricsFeatureProvider = FeatureFactory.getFactory(context).getMetricsFeatureProvider();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        mSearchAdapter = new SearchResultsAdapter(this);
        
        mSearchFeatureProvider.initFeedbackButton();

        final LoaderManager loaderManager = getLoaderManager();

        if (savedInstanceState != null) {
            mQuery = savedInstanceState.getString(STATE_QUERY);
            mNeverEnteredQuery = savedInstanceState.getBoolean(STATE_NEVER_ENTERED_QUERY);
            mResultClickCount = savedInstanceState.getInt(STATE_RESULT_CLICK_COUNT);
            loaderManager.initLoader(LOADER_ID_DATABASE, null, this);
            loaderManager.initLoader(LOADER_ID_INSTALLED_APPS, null, this);
        } else {
            loaderManager.initLoader(LOADER_ID_RECENTS, null, this);
        }

        final Activity activity = getActivity();
        final ActionBar actionBar = activity.getActionBar();
        mSearchView = makeSearchView(actionBar, mQuery);
        actionBar.setCustomView(mSearchView);
        actionBar.setDisplayShowCustomEnabled(true);
        actionBar.setDisplayShowTitleEnabled(false);
        mSearchView.requestFocus();

        // Run the Index update only if we have some space
        if (!Utils.isLowStorage(activity)) {
            mSearchFeatureProvider.updateIndex(activity);
        } else {
            Log.w(TAG, "Cannot update the Indexer as we are running low on storage space!");
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.search_panel_2, container, false);
        mResultsRecyclerView = (RecyclerView) view.findViewById(R.id.list_results);
        mResultsRecyclerView.setAdapter(mSearchAdapter);
        mResultsRecyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
        mResultsRecyclerView.addOnScrollListener(mScrollListener);

        mNoResultsView = (LinearLayout) view.findViewById(R.id.no_results_layout);
        return view;
    }

    @Override
    public void onStop() {
        super.onStop();
        final Activity activity = getActivity();
        if (activity != null && activity.isFinishing()) {
            mMetricsFeatureProvider.histogram(activity, RESULT_CLICK_COUNT, mResultClickCount);
            if (mNeverEnteredQuery) {
                mMetricsFeatureProvider.action(activity,
                        MetricsProto.MetricsEvent.ACTION_LEAVE_SEARCH_RESULT_WITHOUT_QUERY);
            }
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_QUERY, mQuery);
        outState.putBoolean(STATE_NEVER_ENTERED_QUERY, mNeverEnteredQuery);
        outState.putInt(STATE_RESULT_CLICK_COUNT, mResultClickCount);
    }

    @Override
    public boolean onQueryTextChange(String query) {
        if (TextUtils.equals(query, mQuery)) {
            return true;
        }

        final boolean isEmptyQuery = TextUtils.isEmpty(query);

        // Hide no-results-view when the new query is not a super-string of the previous
        if ((mQuery != null) && (mNoResultsView.getVisibility() == View.VISIBLE)
                && (query.length() < mQuery.length())) {
            mNoResultsView.setVisibility(View.GONE);
        }

        mResultClickCount = 0;
        mNeverEnteredQuery = false;
        mQuery = query;
        mSearchAdapter.clearResults();

        if (isEmptyQuery) {
            final LoaderManager loaderManager = getLoaderManager();
            loaderManager.destroyLoader(LOADER_ID_DATABASE);
            loaderManager.destroyLoader(LOADER_ID_INSTALLED_APPS);
            loaderManager.restartLoader(LOADER_ID_RECENTS, null /* args */, this /* callback */);
            mSearchFeatureProvider.hideFeedbackButton();
        } else {
            restartLoaders();
        }

        return true;
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        // Save submitted query.
        getLoaderManager().restartLoader(SaveQueryRecorderCallback.LOADER_ID_SAVE_QUERY_TASK, null,
                mSaveQueryRecorderCallback);
        hideKeyboard();
        return true;
    }

    @Override
    public Loader<List<? extends SearchResult>> onCreateLoader(int id, Bundle args) {
        final Activity activity = getActivity();

        switch (id) {
            case LOADER_ID_DATABASE:
                return mSearchFeatureProvider.getDatabaseSearchLoader(activity, mQuery);
            case LOADER_ID_INSTALLED_APPS:
                return mSearchFeatureProvider.getInstalledAppSearchLoader(activity, mQuery);
            case LOADER_ID_RECENTS:
                return mSearchFeatureProvider.getSavedQueryLoader(activity);
            default:
                return null;
        }
    }

    @Override
    public void onLoadFinished(Loader<List<? extends SearchResult>> loader,
            List<? extends SearchResult> data) {
        mSearchAdapter.addResultsToMap(data, loader.getClass().getName());

        if (mUnfinishedLoadersCount.decrementAndGet() == 0) {
            final int resultCount = mSearchAdapter.mergeResults();
            mSearchFeatureProvider.showFeedbackButton(this, getView());

            if (resultCount == 0) {
                mNoResultsView.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    public void onLoaderReset(Loader<List<? extends SearchResult>> loader) {
    }

    public void onSearchResultClicked() {
        mResultClickCount++;
    }

    public void onSavedQueryClicked(CharSequence query) {
        final String queryString = query.toString();
        mSearchView.setQuery(queryString, false /* submit */);
        onQueryTextChange(queryString);
    }

    private void restartLoaders() {
        final LoaderManager loaderManager = getLoaderManager();
        mUnfinishedLoadersCount.set(NUM_QUERY_LOADERS);
        loaderManager.restartLoader(LOADER_ID_DATABASE, null /* args */, this /* callback */);
        loaderManager.restartLoader(LOADER_ID_INSTALLED_APPS, null /* args */, this /* callback */);
    }

    public String getQuery() {
        return mQuery;
    }

    public List<SearchResult> getSearchResults() {
        return mSearchAdapter.getSearchResults();
    }

    @VisibleForTesting (otherwise = VisibleForTesting.PRIVATE)
    SearchView makeSearchView(ActionBar actionBar, String query) {
        final SearchView searchView = new SearchView(actionBar.getThemedContext());
        searchView.setIconifiedByDefault(false);
        searchView.setQuery(query, false /* submitQuery */);
        searchView.setOnQueryTextListener(this);
        searchView.setTag(SEARCH_TAG, searchView);
        final LayoutParams lp =
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        searchView.setLayoutParams(lp);
        return searchView;
    }

    private void hideKeyboard() {
        final Activity activity = getActivity();
        if (activity != null) {
            View view = activity.getCurrentFocus();
            InputMethodManager imm = (InputMethodManager)
                    activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }

        if (mResultsRecyclerView != null) {
            mResultsRecyclerView.requestFocus();
        }
    }

    private class SaveQueryRecorderCallback implements LoaderManager.LoaderCallbacks<Void> {
        // TODO: make a generic background task manager to handle one-off tasks like this one.

        private static final int LOADER_ID_SAVE_QUERY_TASK = 0;

        @Override
        public Loader<Void> onCreateLoader(int id, Bundle args) {
            return new SavedQueryRecorder(getActivity(), mQuery);
        }

        @Override
        public void onLoadFinished(Loader<Void> loader, Void data) {

        }

        @Override
        public void onLoaderReset(Loader<Void> loader) {

        }
    }
}