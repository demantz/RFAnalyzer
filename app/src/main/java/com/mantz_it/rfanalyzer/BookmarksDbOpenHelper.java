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

		// DEBUG
		populateExampleBookmarks(db);
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

	private void populateExampleBookmarks(SQLiteDatabase db) {
		db.execSQL("INSERT INTO bookmarkCategories (categoryName, description) VALUES ('category1', 'Just for testing');");
		db.execSQL("INSERT INTO bookmarkCategories (categoryName, description) VALUES ('FM Stations', 'Some FM radio stations');");
		db.execSQL("INSERT INTO bookmarkCategories (categoryName, description) VALUES ('Favorites', 'My favorite stations');");

		db.execSQL("INSERT INTO bookmarks (name, comment, categoryID, frequency, channelWidth, mode, squelch) VALUES ('bookmark1', 'Just for testing1', 1, 10012301, 10000, 2, -60);");
		db.execSQL("INSERT INTO bookmarks (name, comment, categoryID, frequency, channelWidth, mode, squelch) VALUES ('bookmark2', 'Just for testing2', 1, 10012302, 10000, 1, -50);");
		db.execSQL("INSERT INTO bookmarks (name, comment, categoryID, frequency, channelWidth, mode, squelch) VALUES ('bookmark3', 'Just for testing3', 1, 10012303, 10000, 2, -40);");
		db.execSQL("INSERT INTO bookmarks (name, comment, categoryID, frequency, channelWidth, mode, squelch) VALUES ('bookmark4', 'Just for testing4', 1, 10012304, 10000, 3, -30);");
		db.execSQL("INSERT INTO bookmarks (name, comment, categoryID, frequency, channelWidth, mode, squelch) VALUES ('bookmark5', 'Just for testing5', 1, 10012305, 10000, 4, -60);");
		db.execSQL("INSERT INTO bookmarks (name, comment, categoryID, frequency, channelWidth, mode, squelch) VALUES ('bookmark6', 'Just for testing6', 1, 10012306, 10000, 2, -50);");
		db.execSQL("INSERT INTO bookmarks (name, comment, categoryID, frequency, channelWidth, mode, squelch) VALUES ('bookmark7', 'Just for testing7', 1, 10012307, 10000, 2, -40);");
		db.execSQL("INSERT INTO bookmarks (name, comment, categoryID, frequency, channelWidth, mode, squelch) VALUES ('bookmark8', 'Just for testing8', 1, 10012308, 10000, 2, -30);");
		db.execSQL("INSERT INTO bookmarks (name, comment, categoryID, frequency, channelWidth, mode, squelch) VALUES ('bookmark9', 'Just for testing9', 1, 10012309, 10000, 2, -20);");
		db.execSQL("INSERT INTO bookmarks (name, comment, categoryID, frequency, channelWidth, mode, squelch) VALUES ('DasDing!', 'Cool radio station!', 2, 96000000, 100000, 3, -60);");
	}
}
