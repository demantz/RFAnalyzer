package com.mantz_it.rfanalyzer;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;

import com.mantz_it.rfanalyzer.BookmarksContract.BookmarkCategories;
import com.mantz_it.rfanalyzer.BookmarksContract.Bookmarks;

/**
 * Created by dennis on 30/03/15.
 */
public class BookmarksProvider extends ContentProvider {
	private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
	private static final String PROVIDER_NAME = "com.mantz_it.rfanalyzer.provider";
	private static final int URIMATCHER_BOOKMARKS = 1;
	private static final int URIMATCHER_BOOKMARKS_ID = 2;
	private static final int URIMATCHER_BOOKMARKS_CATEGORIES = 3;
	private static final int URIMATCHER_BOOKMARKS_CATEGORIES_ID = 4;
	private static final String BOOKMARKS_TYPE = "vnd.android.cursor.dir/vnd.com.mantz_it.rfanalyzer.provider.bookmarks";
	private static final String BOOKMARK_TYPE = "vnd.android.cursor.item/vnd.com.mantz_it.rfanalyzer.provider.bookmarks";
	private static final String BOOKMARK_CATEGORIES_TYPE = "vnd.android.cursor.dir/vnd.com.mantz_it.rfanalyzer.provider.bookmarkCategories";
	private static final String BOOKMARK_CATEGORY_TYPE = "vnd.android.cursor.item/vnd.com.mantz_it.rfanalyzer.provider.bookmarkCategories";
	private BookmarksDbOpenHelper dbHelper;

	// Preparing the UriMatcher:
	static {
		uriMatcher.addURI(PROVIDER_NAME, "bookmarks", URIMATCHER_BOOKMARKS);
		uriMatcher.addURI(PROVIDER_NAME, "bookmarks/#", URIMATCHER_BOOKMARKS_ID);
		uriMatcher.addURI(PROVIDER_NAME, "bookmarks/categories", URIMATCHER_BOOKMARKS_CATEGORIES);
		uriMatcher.addURI(PROVIDER_NAME, "bookmarks/categories/#", URIMATCHER_BOOKMARKS_CATEGORIES_ID);
	}

	@Override
	public boolean onCreate() {
		Context context = getContext();
		dbHelper = new BookmarksDbOpenHelper(context);
		return true;
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
		SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
		SQLiteDatabase db = dbHelper.getReadableDatabase();

		switch (uriMatcher.match(uri)) {
			case URIMATCHER_BOOKMARKS_ID:
				qb.appendWhere( Bookmarks._ID + "=" + ContentUris.parseId(uri));
				// no break: continue with URIMATCHER_BOOKMARKS as well!
			case URIMATCHER_BOOKMARKS:
				qb.setTables(Bookmarks.TABLE_NAME);
				if (sortOrder == null || sortOrder.length() == 0)
					sortOrder = Bookmarks.COLUMN_NAME_FREQUENCY + " ASC";
				break;

			case URIMATCHER_BOOKMARKS_CATEGORIES_ID:
				qb.appendWhere( BookmarkCategories._ID + "=" + ContentUris.parseId(uri));
				// no break: continue with URIMATCHER_BOOKMARKS_CATEGORIES as well!
			case URIMATCHER_BOOKMARKS_CATEGORIES:
				qb.setTables(BookmarkCategories.TABLE_NAME);
				if (sortOrder == null || sortOrder.length() == 0)
					sortOrder = BookmarkCategories.COLUMN_NAME_CATEGORY_NAME + " ASC";
				break;

			default:
				throw new IllegalArgumentException("Unknown URI " + uri);
		}

		// execute the query
		Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, sortOrder);

		// Register to watch a content uri for changes:
		if(c != null)
			c.setNotificationUri(getContext().getContentResolver(), uri);

		return c;
	}

	@Override
	public String getType(Uri uri) {
		switch (uriMatcher.match(uri)) {
			case URIMATCHER_BOOKMARKS:
				return BOOKMARKS_TYPE;
			case URIMATCHER_BOOKMARKS_ID:
				return BOOKMARK_TYPE;
			case URIMATCHER_BOOKMARKS_CATEGORIES:
				return BOOKMARK_CATEGORIES_TYPE;
			case URIMATCHER_BOOKMARKS_CATEGORIES_ID:
				return BOOKMARK_CATEGORY_TYPE;
			default:
				throw new IllegalArgumentException("Unknown URI: " + uri);
		}
	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		Uri _uri = null;
		long _ID = -1;
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		switch (uriMatcher.match(uri)){
			case URIMATCHER_BOOKMARKS:
				_ID = db.insert(Bookmarks.TABLE_NAME, "", values);
				break;
			case URIMATCHER_BOOKMARKS_CATEGORIES:
				_ID = db.insert(BookmarkCategories.TABLE_NAME, "", values);
				break;
			default: throw new SQLException("Failed to insert row into " + uri);
		}
		if (_ID > 0) {
			_uri = ContentUris.withAppendedId(uri, _ID);
			getContext().getContentResolver().notifyChange(_uri, null);
		}
		return _uri;
	}

	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		String _selection;
		String _table;
		switch (uriMatcher.match(uri)){
			case URIMATCHER_BOOKMARKS:
				_selection = selection;
				_table = Bookmarks.TABLE_NAME;
				break;
			case URIMATCHER_BOOKMARKS_ID:
				_selection = selection==null ? "" : selection + " AND ";
				_selection += Bookmarks._ID + " = " + ContentUris.parseId(uri);
				_table = Bookmarks.TABLE_NAME;
				break;
			case URIMATCHER_BOOKMARKS_CATEGORIES:
				_selection = selection;
				_table = BookmarkCategories.TABLE_NAME;
				break;
			case URIMATCHER_BOOKMARKS_CATEGORIES_ID:
				_selection = selection==null ? "" : selection + " AND ";
				_selection += BookmarkCategories._ID + " = " + ContentUris.parseId(uri);
				_table=BookmarkCategories.TABLE_NAME;
				break;
			default: throw new SQLException("Failed to insert row into " + uri);
		}
		return db.delete(_table, _selection, selectionArgs);
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		String _selection;
		String _table;
		switch (uriMatcher.match(uri)){
			case URIMATCHER_BOOKMARKS:
				_selection = selection;
				_table = Bookmarks.TABLE_NAME;
				break;
			case URIMATCHER_BOOKMARKS_ID:
				_selection = selection==null ? "" : selection + " AND ";
				_selection += Bookmarks._ID + " = " + ContentUris.parseId(uri);
				_table = Bookmarks.TABLE_NAME;
				break;
			case URIMATCHER_BOOKMARKS_CATEGORIES:
				_selection = selection;
				_table = BookmarkCategories.TABLE_NAME;
				break;
			case URIMATCHER_BOOKMARKS_CATEGORIES_ID:
				_selection = selection==null ? "" : selection + " AND ";
				_selection += BookmarkCategories._ID + " = " + ContentUris.parseId(uri);
				_table=BookmarkCategories.TABLE_NAME;
				break;
			default: throw new SQLException("Failed to insert row into " + uri);
		}
		return db.update(_table, values, _selection, selectionArgs);
	}
}
