package com.groceryotg.android.utils;

import android.content.Context;
import android.database.Cursor;
import com.groceryotg.android.database.CategoryTable;
import com.groceryotg.android.database.StoreParentTable;
import com.groceryotg.android.database.StoreTable;
import com.groceryotg.android.database.contentprovider.GroceryotgProvider;

/**
 * User: robert
 * Date: 14/03/13
 */
public class GroceryOTGUtils {

    public static Cursor getStoreLocations(Context context) {
        String[] projection = {StoreParentTable.COLUMN_STORE_PARENT_NAME, StoreTable.COLUMN_STORE_LATITUDE, StoreTable.COLUMN_STORE_LONGITUDE};
        Cursor c = context.getContentResolver().query(GroceryotgProvider.CONTENT_URI_STO_JOIN_STOREPARENT, projection, null, null, null);
        return c;
    }

    public static Cursor getStoreParentNames(Context context) {
        String[] projection = {StoreParentTable.COLUMN_STORE_PARENT_ID, StoreParentTable.COLUMN_STORE_PARENT_NAME};
        Cursor c = context.getContentResolver().query(GroceryotgProvider.CONTENT_URI_STOPARENT, projection, null, null, null);
        return c;
    }

    public static Cursor getCategories(Context context) {
        String[] projection = {CategoryTable.COLUMN_CATEGORY_ID, CategoryTable.COLUMN_CATEGORY_NAME};
        Cursor c = context.getContentResolver().query(GroceryotgProvider.CONTENT_URI_CAT, projection, null, null, null);
        return c;
    }

}
