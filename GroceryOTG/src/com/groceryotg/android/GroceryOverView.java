package com.groceryotg.android;

import java.util.ArrayList;
import java.util.List;

import android.app.LoaderManager;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.SearchView.OnCloseListener;
import android.widget.SearchView.OnQueryTextListener;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.Toast;
import com.actionbarsherlock.app.SherlockListActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.groceryotg.android.database.CartTable;
import com.groceryotg.android.database.CategoryTable;
import com.groceryotg.android.database.GroceryTable;
import com.groceryotg.android.database.contentprovider.GroceryotgProvider;
import com.groceryotg.android.services.NetworkHandler;
import com.groceryotg.android.services.ServerURL;
import com.groceryotg.android.utils.RefreshAnimation;

/**
 * User: robert
 * Date: 07/02/13
 */
public class GroceryOverView extends SherlockListActivity implements OnQueryTextListener, OnCloseListener, 
						LoaderManager.LoaderCallbacks<Cursor> {
    private SimpleCursorAdapter adapter;
    private MenuItem refreshItem;
    private Uri groceryUri;
    private String categoryName;
    private Integer categoryId;
    
    // User search
    private String mQuery;
    private SearchView mSearchView;

    // Filters
    private Integer storeId;
    private Integer subcategoryId;
    private Double mPriceRangeMin;
    private Double mPriceRangeMax;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.grocery_list);
        
        // Enable ancestral navigation ("Up" button in ActionBar) for Android < 4.1
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        
        Bundle extras = getIntent().getExtras();
        groceryUri = (savedInstanceState == null) ? null : (Uri) savedInstanceState.getParcelable(GroceryotgProvider.CONTENT_ITEM_TYPE_CAT);
        
        if (extras != null) {
            groceryUri = extras.getParcelable(GroceryotgProvider.CONTENT_ITEM_TYPE_CAT);
            String[] projection = {CategoryTable.COLUMN_CATEGORY_NAME, CategoryTable.COLUMN_CATEGORY_ID};
            Cursor cursor = getContentResolver().query(groceryUri, projection, null, null, null);

            if (cursor != null) {
                cursor.moveToFirst();
                categoryName = cursor.getString(cursor.getColumnIndexOrThrow(CategoryTable.COLUMN_CATEGORY_NAME));
                categoryId = cursor.getInt(cursor.getColumnIndexOrThrow(CategoryTable.COLUMN_CATEGORY_ID));

                this.getActionBar().setTitle(categoryName);
            }
        }
        
        // Initialize the user query to blank
    	mQuery = "";
    	
    	this.getListView().setDividerHeight(2);
        fillData();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getSupportMenuInflater();
        inflater.inflate(R.menu.groceryoverview_menu, menu);
        
     	// Get the SearchView and set the searchable configuration
        SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        mSearchView = (SearchView) menu.findItem(R.id.search).getActionView();
        mSearchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
        
        // If set to "true" the icon is displayed within the EditText, if set to "false" it is displayed outside
        mSearchView.setIconifiedByDefault(true);
        
        // Instead of invoking activity again, use onQueryTextListener when a search is performed
        mSearchView.setOnQueryTextListener(this);
        mSearchView.setOnCloseListener(this);
        
        // Add callbacks to the menu item that contains the SearchView in order to capture 
        // the event of pressing the 'back' button
        MenuItem searchItem = (MenuItem) menu.findItem(R.id.search);
        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                // This is called when the user clicks on the magnifying glass icon to 
            	// expand the search view widget.
                return true;
            }
            
            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
            	// This is called the user presses the 'back' button to exit the collapsed
            	// search widget view (i.e., to close the search). Here, refresh the query
            	// to display the whole list of items:
                clearQuery();
                return true;
            }
        });
        
        return true;
    }

    public void clearQuery() {
    	mQuery = "";
    	getLoaderManager().restartLoader(0, null, this);
    }
    
	@Override
    public boolean onQueryTextSubmit(String query) {
        /*
         * You don't need to deal with "appData" and passing bundles back to the search
         * activity, because you already have the search query here.
         */
		String newQuery = !TextUtils.isEmpty(query) ? query : null;
		
		// Don't do anything if the query hasn't changed
		if (newQuery == null && mQuery == null) {
			return true;
		}
		if (newQuery != null && mQuery.equals(newQuery)) {
			return true;
		}
		
		mQuery = newQuery;
		getLoaderManager().restartLoader(0, null, this);
		
        return true;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        // This is called when you click the search icon or type characters in the search widget
    	// (called on every keystroke)
        return true;
    }
    
    @Override
    public boolean onClose() {
    	/*
    	 * This method NEVER gets called IF the SearchView is set to be collapsible 
    	 * (showAsAction=collapsibleActionView in the XML file). Making it collapsible 
    	 * means that the search widget shows up in the top action bar instead of at the
    	 * bottom. With a collapsible SearchView, the 'x' button only clears the text in
    	 * the EditText field. After pressing it once, the 'x' disappears.
    	 * 
    	 * With a non-collapsible SearchView, the 'x' button pressed once clears the text
    	 * in the EditText field, and then (it is still visible) pressing it again invokes this method.
    	 */
        if (!TextUtils.isEmpty(mSearchView.getQuery())) {
            mSearchView.setQuery(null, true);
        }
        
        // Refresh the list to display all items again and hide the search view
        clearQuery();
        mSearchView.setVisibility(SearchView.GONE);
        
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.refresh:
                refreshItem = item;
                RefreshAnimation.refreshIcon(this, true, refreshItem);
                refreshCurrentGrocery();
                return true;
            case R.id.map:
                launchMapActivity();
                return true;
            case R.id.shop_cart:
                launchShopCartActivity();
                return true;
            case android.R.id.home:
            	// This is called when the Home (Up) button is pressed
                // in the Action Bar. This handles Android < 4.1.
            	
            	// Specify the parent activity
            	Intent parentActivityIntent = new Intent(this, CategoryOverView.class);
            	parentActivityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | 
            								Intent.FLAG_ACTIVITY_NEW_TASK);
            	startActivity(parentActivityIntent);
            	finish();
            	return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        super.onListItemClick(l, v, position, id);
        TextView textView = (TextView) v.findViewById(R.id.grocery_row_label);

        ContentValues values = new ContentValues();
        values.put(CartTable.COLUMN_CART_GROCERY_NAME, textView.getText().toString());

        getContentResolver().insert(GroceryotgProvider.CONTENT_URI_CART_ITEM, values);

        Toast t = Toast.makeText(this, "Item added to Shopping Cart", Toast.LENGTH_SHORT);
        t.show();
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

    private void refreshCurrentGrocery() {
        Intent intent = new Intent(this, NetworkHandler.class);

        PendingIntent pendingIntent = createPendingResult(1, intent, PendingIntent.FLAG_ONE_SHOT);
        intent.putExtra(NetworkHandler.REFRESH_CONTENT, NetworkHandler.GRO);
        intent.putExtra("pendingIntent", pendingIntent);

        startService(intent);
    }

    private void fillData() {
    	
    	String[] from = new String[]{GroceryTable.COLUMN_GROCERY_NAME, GroceryTable.COLUMN_GROCERY_PRICE, GroceryTable.COLUMN_GROCERY_STORE};
        int[] to = new int[]{R.id.grocery_row_label, R.id.grocery_row_price, R.id.grocery_row_store};
    	
        adapter = new SimpleCursorAdapter(this, R.layout.grocery_row, null, from, to, 0);
        adapter.setViewBinder(new SimpleCursorAdapter.ViewBinder() {
            @Override
            public boolean setViewValue(View view, Cursor cursor, int columnIndex) {
                if (columnIndex == 2) {
                    TextView textView = (TextView) view;
                    if (cursor.getDouble(columnIndex) != 0) {
                        textView.setText("$" + ServerURL.getGetDecimalFormat().format(cursor.getDouble(columnIndex)));
                    } else {
                        textView.setText("N/A");
                    }
                    return true;
                }
                return false;
            }
        });

        setListAdapter(adapter);
        displayEmptyListMessage();
        
        // Prepare the asynchronous loader.
        getLoaderManager().initLoader(0, null, this);
    }
    
    private void displayEmptyListMessage() {
        String emptyStringFormat = getResources().getString(R.string.no_new_content);
        String emptyStringMsg = (ServerURL.getLastRefreshed() == null) ? String.format(emptyStringFormat, " Never") : String.format(emptyStringFormat, ServerURL.getLastRefreshed());
        ListView myListView = this.getListView();
        TextView myTextView = (TextView) findViewById(R.id.empty_grocery_list);
        myTextView.setText(emptyStringMsg);
        myListView.setEmptyView(myTextView);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Toast toast = null;
        RefreshAnimation.refreshIcon(this, false, refreshItem);
        if (resultCode == NetworkHandler.CONNECTION) {
            toast = Toast.makeText(this, "Groceries Updated", Toast.LENGTH_LONG);
        } else if (resultCode == NetworkHandler.NO_CONNECTION) {
            toast = Toast.makeText(this, "No Internet Connection", Toast.LENGTH_LONG);
        }
        assert toast != null;
        toast.show();
        displayEmptyListMessage();
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        
    	CursorLoader returnValue = null;
    	String[] projection = {GroceryTable.COLUMN_ID, GroceryTable.COLUMN_GROCERY_NAME, 
    							GroceryTable.COLUMN_GROCERY_PRICE, GroceryTable.COLUMN_GROCERY_STORE};
        String selection = GroceryTable.COLUMN_GROCERY_CATEGORY + "=?";
    	List<String> selectionArgs = new ArrayList<String>();
    	selectionArgs.add(categoryId.toString());
        
        // If user entered a search query, filter the results based on grocery name
        if (!mQuery.isEmpty()) {
    		selection += " AND " + GroceryTable.COLUMN_GROCERY_NAME + " LIKE ?";
    		selectionArgs.add("%" + mQuery + "%");
        }
        if (storeId != null) {
        	selection += " AND " + GroceryTable.COLUMN_GROCERY_STORE + " = ?";
        	selectionArgs.add(storeId.toString());
        }
        
        final String[] selectionArgsArr = new String[selectionArgs.size()];
        selectionArgs.toArray(selectionArgsArr);
        returnValue = new CursorLoader(this, GroceryotgProvider.CONTENT_URI_GRO, projection, selection, selectionArgsArr, null);
        return returnValue;
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        adapter.swapCursor(data);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
    	// Called when a previously created loader is being reset, thus making its 
    	// data unavailable
        adapter.swapCursor(null);
    }
}
