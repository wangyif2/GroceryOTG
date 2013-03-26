package com.groceryotg.android;

import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.view.ViewPager;
import android.util.SparseIntArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.groceryotg.android.database.CategoryTable;
import com.groceryotg.android.database.StoreParentTable;
import com.groceryotg.android.database.contentprovider.GroceryotgProvider;
import com.groceryotg.android.fragment.CategoryGridFragment;
import com.groceryotg.android.fragment.GroceryListFragment;
import com.groceryotg.android.fragment.MyFlyerFragment;
import com.groceryotg.android.services.NetworkHandler;
import com.groceryotg.android.settings.SettingsActivity;
import com.groceryotg.android.utils.GroceryOTGUtils;
import com.groceryotg.android.utils.RefreshAnimation;
import com.slidingmenu.lib.SlidingMenu;

import java.util.HashMap;
import java.util.Map;

/**
 * User: robert
 * Date: 16/03/13
 */
public class GroceryFragmentActivity extends SherlockFragmentActivity {
    static HashMap<Integer, String> categories;

    public static Context mContext;
    public static ViewPager mPager;
    GroceryAdapter mAdapter;
    SlidingMenu mSlidingMenu;
    RefreshStatusReceiver mRefreshStatusReceiver;
    MenuItem refreshItem;
    Menu menu;

    public static String myQuery;

    public static Map<Integer, String> storeNames;
    public static SparseIntArray storeSelected;

    public static Double mPriceRangeMin;
    public static Double mPriceRangeMax;

    public static final Integer SELECTED = 1;
    public static final Integer NOT_SELECTED = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.grocery_pager);

        Bundle extras = getIntent().getExtras();
        categories = getCategoryInfo();
        mContext = this;
        setMyQuery("");

        setStoreInformation();

        configActionBar();

        configViewPager();

        configSlidingMenu();
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            // Gets the search query from the voice recognizer intent
            String query = intent.getStringExtra(SearchManager.QUERY);

            if (mPager.getCurrentItem() > 0) {
                // Set the search box text to the received query and submit the search
                // from within the fragment if not on the category overview page:
                mAdapter.getFragment(mPager.getCurrentItem()).handleVoiceSearch(query);
            } else {
                // If on the home page and doing a global search, send the intent
                // to the GlobalSearchActivity
                Intent globalSearchIntent = new Intent(this, GlobalSearchActivity.class);
                GroceryOTGUtils.copyIntentData(intent, globalSearchIntent);
                globalSearchIntent.putExtra(GlobalSearchActivity.GLOBAL_SEARCH, true);
                startActivity(globalSearchIntent);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mRefreshStatusReceiver = new RefreshStatusReceiver();
        IntentFilter mStatusIntentFilter = new IntentFilter(NetworkHandler.REFRESH_COMPLETED_ACTION);
        mStatusIntentFilter.addCategory(Intent.CATEGORY_DEFAULT);
        LocalBroadcastManager.getInstance(this).registerReceiver(mRefreshStatusReceiver, mStatusIntentFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mRefreshStatusReceiver);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getSupportMenuInflater();
        inflater.inflate(R.menu.grocery_pager_menu, menu);
        this.menu = menu;

        // Get the SearchView and set the searchable configuration
        SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        SearchView searchView = (SearchView) menu.findItem(R.id.search).getActionView();
        searchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
        searchView.setIconifiedByDefault(true);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.refresh:
                refreshCurrentPager();
                return true;
            case R.id.map:
                launchMapActivity();
                return true;
            case R.id.shop_cart:
                launchShopCartActivity();
                return true;
            case android.R.id.home:
                launchSlidingMenu();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private HashMap<Integer, String> getCategoryInfo() {
        HashMap<Integer, String> categories = new HashMap<Integer, String>();
        Cursor c = getContentResolver().query(GroceryotgProvider.CONTENT_URI_CAT, null, null, null, null);

        c.moveToFirst();
        while (!c.isAfterLast()) {
            categories.put(
                    c.getInt(c.getColumnIndexOrThrow(CategoryTable.COLUMN_CATEGORY_ID)),
                    c.getString(c.getColumnIndexOrThrow(CategoryTable.COLUMN_CATEGORY_NAME)));
            c.moveToNext();
        }
        return categories;
    }

    private void setStoreInformation() {
        // Initialize the list of stores from database
        storeSelected = new SparseIntArray();  // {storeParentId, selectedFlag}
        storeNames = new HashMap<Integer, String>(); // {storeParentId, storeParentName}

        Cursor storeCursor = GroceryOTGUtils.getStoreParentNamesCursor(this);
        if (storeCursor != null) {
            storeCursor.moveToFirst();
            while (!storeCursor.isAfterLast()) {
                storeSelected.put(storeCursor.getInt(storeCursor.getColumnIndex(StoreParentTable.COLUMN_STORE_PARENT_ID)), SELECTED);
                storeNames.put(storeCursor.getInt(storeCursor.getColumnIndex(StoreParentTable.COLUMN_STORE_PARENT_ID)),
                        storeCursor.getString(storeCursor.getColumnIndex(StoreParentTable.COLUMN_STORE_PARENT_NAME)));
                storeCursor.moveToNext();
            }
        }
    }

    public static void setMyQuery(String mQuery) {
        GroceryFragmentActivity.myQuery = mQuery;
    }

    private void configActionBar() {
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    private void configViewPager() {
        mAdapter = new GroceryAdapter(getSupportFragmentManager());

        mPager = (ViewPager) findViewById(R.id.pager);
        mPager.setAdapter(mAdapter);
    }

    private void configSlidingMenu() {
        mSlidingMenu = new SlidingMenu(this);
        mSlidingMenu.setMode(SlidingMenu.LEFT);
        mSlidingMenu.setShadowWidthRes(R.dimen.shadow_width);
        mSlidingMenu.setShadowDrawable(R.drawable.shadow);
        mSlidingMenu.setBehindOffsetRes(R.dimen.slidingmenu_offset);
        mSlidingMenu.setFadeDegree(0.35f);
        mSlidingMenu.setTouchModeAbove(SlidingMenu.TOUCHMODE_MARGIN);
        mSlidingMenu.attachToActivity(this, SlidingMenu.SLIDING_CONTENT);
        mSlidingMenu.setMenu(R.layout.menu_frame);

        // Populate the SlidingMenu
        String[] slidingMenuItems = new String[]{getString(R.string.slidingmenu_item_cat),
                getString(R.string.slidingmenu_item_cart),
                getString(R.string.slidingmenu_item_map),
                getString(R.string.slidingmenu_item_sync),
                getString(R.string.slidingmenu_item_settings),
                getString(R.string.slidingmenu_item_about)};

        ListView menuView = (ListView) findViewById(R.id.menu_items);
        ArrayAdapter<String> menuAdapter = new ArrayAdapter<String>(this,
                R.layout.menu_item, android.R.id.text1, slidingMenuItems);
        menuView.setAdapter(menuAdapter);

        menuView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view,
                                    int position, long id) {
                // Switch activity based on what mSlidingMenu item the user selected
                TextView textView = (TextView) view;
                String selectedItem = textView.getText().toString();

                if (mSlidingMenu.isMenuShowing())
                    mSlidingMenu.showContent();

                if (selectedItem.equalsIgnoreCase(getString(R.string.slidingmenu_item_cat))) {
                    // Selected Categories
                    mPager.setCurrentItem(0);
                } else if (selectedItem.equalsIgnoreCase(getString(R.string.slidingmenu_item_cart))) {
                    // Selected Shopping Cart
                    launchShopCartActivity();
                } else if (selectedItem.equalsIgnoreCase(getString(R.string.slidingmenu_item_map))) {
                    // Selected Map
                    launchMapActivity();
                } else if (selectedItem.equalsIgnoreCase(getString(R.string.slidingmenu_item_sync))) {
                    // Selected Sync
                    refreshCurrentPager();
                } else if (selectedItem.equalsIgnoreCase(getString(R.string.slidingmenu_item_settings))) {
                    // Selected Settings
                    launchSettingsActivity();
                } else if (selectedItem.equalsIgnoreCase(getString(R.string.slidingmenu_item_about))) {
                    // Selected About
                    //startActivity(new Intent(CategoryOverView.this, About.class));
                }
            }
        });
    }

    private void launchSettingsActivity() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    private void launchSlidingMenu() {
        if (!mSlidingMenu.isMenuShowing())
            mSlidingMenu.showMenu();
        else
            mSlidingMenu.showContent();
    }

    private void launchShopCartActivity() {
        Intent intent = new Intent(this, ShopCartOverView.class);
        startActivity(intent);
    }

    private void launchMapActivity() {
        Intent intent = new Intent(this, GroceryMapView.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private void refreshCurrentPager() {
        Toast t = Toast.makeText(this, "Starting fetching new items...", Toast.LENGTH_LONG);
        t.show();

        Intent intent = new Intent(mContext, NetworkHandler.class);
        if (mPager.getCurrentItem() == 0) {
            refreshItem = menu.findItem(R.id.refresh);
            RefreshAnimation.refreshIcon(mContext, true, refreshItem);
            intent.putExtra(NetworkHandler.REFRESH_CONTENT, NetworkHandler.CAT);
        } else
            intent.putExtra(NetworkHandler.REFRESH_CONTENT, NetworkHandler.GRO);
        startService(intent);
    }

    private class RefreshStatusReceiver extends BroadcastReceiver {
        private RefreshStatusReceiver() {

        }

        @Override
        public void onReceive(Context context, Intent intent) {
            int resultCode = intent.getBundleExtra("bundle").getInt(NetworkHandler.CONNECTION_STATE);
            int requestType = intent.getBundleExtra("bundle").getInt(NetworkHandler.REQUEST_TYPE);

            Toast toast = null;
            if (requestType == NetworkHandler.CAT) {
                RefreshAnimation.refreshIcon(context, false, refreshItem);
            }
            if (resultCode == NetworkHandler.CONNECTION) {
                toast = Toast.makeText(mContext, "Groceries Updated", Toast.LENGTH_LONG);
            } else if (resultCode == NetworkHandler.NO_CONNECTION) {
                toast = Toast.makeText(mContext, "No Internet Connection", Toast.LENGTH_LONG);
            }
            assert toast != null;
            toast.show();
        }
    }

    public static class GroceryAdapter extends FragmentStatePagerAdapter {

        private static final int POSITION_CATEGORY = 0;
        private static final int POSITION_MYFLYER_PAGER = 1;
        private static final String TITLE_PAGER_CATEGORY = "categories overview";
        private static final String TITLE_PAGER_MYFLYER_PAGER = "my flyers";

        private HashMap<Integer, GroceryListFragment> mPageReferenceMap;

        public GroceryAdapter(FragmentManager fm) {
            super(fm);
            mPageReferenceMap = new HashMap<Integer, GroceryListFragment>();
        }

        @Override
        public CharSequence getPageTitle(int position) {
            if (position == POSITION_CATEGORY) {
                return TITLE_PAGER_CATEGORY;
            } else if (position == POSITION_MYFLYER_PAGER)
                return TITLE_PAGER_MYFLYER_PAGER;
            else
                // The hashmap is offset by the position of myflyer pager
                return categories.get(position - POSITION_MYFLYER_PAGER);
        }

        @Override
        public Fragment getItem(int i) {
            if (i == POSITION_CATEGORY) {
                return new CategoryGridFragment();
            } else if (i == POSITION_MYFLYER_PAGER)
                return new MyFlyerFragment();
            else {
                GroceryListFragment myFragment;
                if (mPageReferenceMap.get(i) == null) {
                    myFragment = GroceryListFragment.newInstance(i);
                    mPageReferenceMap.put(i, myFragment);
                    return myFragment;
                } else {
                    return mPageReferenceMap.get(i);
                }
            }
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            super.destroyItem(container, position, object);
            mPageReferenceMap.remove(position);
        }

        @Override
        public int getCount() {
            //the plus 1 here is for the overview front page
            return categories.size() + 2;
        }

        public GroceryListFragment getFragment(int key) {
            return mPageReferenceMap.get(key);
        }
    }
}
