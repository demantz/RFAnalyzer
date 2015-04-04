package com.mantz_it.rfanalyzer;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.mantz_it.rfanalyzer.BookmarksContract.Bookmarks;
import com.mantz_it.rfanalyzer.BookmarksContract.BookmarkCategories;

/**
 * Created by dennis on 23/03/15.
 */
public class BookmarksDbOpenHelper extends SQLiteOpenHelper {

	public static final String LOGTAG = "BookmarksDbOpenHelper";
	public static final int DATABASE_VERSION = 1;
	public static final String DATABASE_NAME = "Bookmarks.db";
	private static final String SQL_CREATE_CATEGORIES_TABLE =
			"CREATE TABLE " + BookmarkCategories.TABLE_NAME + " (" +
					BookmarkCategories._ID + " INTEGER PRIMARY KEY, " +
					BookmarkCategories.COLUMN_NAME_CATEGORY_NAME + " TEXT, " +
					BookmarkCategories.COLUMN_NAME_DESCRIPTION + " TEXT" +
			" );";
	private static final String SQL_CREATE_BOOKMARKS_TABLE =
			"CREATE TABLE " + Bookmarks.TABLE_NAME + " (" +
					Bookmarks._ID + " INTEGER PRIMARY KEY, " +
					Bookmarks.COLUMN_NAME_NAME + " TEXT, " +
					Bookmarks.COLUMN_NAME_COMMENT + " TEXT, " +
					Bookmarks.COLUMN_NAME_CATEGORY_ID + " INTEGER REFERENCES "
								+ BookmarkCategories.TABLE_NAME + " (" + BookmarkCategories._ID + ") ON DELETE CASCADE, " +
					Bookmarks.COLUMN_NAME_FREQUENCY + " INTEGER, " +
					Bookmarks.COLUMN_NAME_CHANNEL_WIDTH + " INTEGER, " +
					Bookmarks.COLUMN_NAME_MODE + " INTEGER, " +
					Bookmarks.COLUMN_NAME_SQUELCH + " REAL " +
			" );";

	public BookmarksDbOpenHelper(Context context) {
		super(context, DATABASE_NAME, null, DATABASE_VERSION);
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		// activate foreign key constraints:
		db.execSQL("PRAGMA foreign_keys = ON;");
		Log.i(LOGTAG, "Creating database: " + SQL_CREATE_CATEGORIES_TABLE);
		db.execSQL(SQL_CREATE_CATEGORIES_TABLE);
		Log.i(LOGTAG, "Creating database: " + SQL_CREATE_BOOKMARKS_TABLE);
		db.execSQL(SQL_CREATE_BOOKMARKS_TABLE);
	}

	@Override
	public void onOpen(SQLiteDatabase db) {
		super.onOpen(db);
		if(!db.isReadOnly())
			db.execSQL("PRAGMA foreign_keys = ON;");
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		// implement this method for database versions > 1
	}
}
