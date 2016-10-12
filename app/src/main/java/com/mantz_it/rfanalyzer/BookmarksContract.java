package com.mantz_it.rfanalyzer;

import android.net.Uri;
import android.provider.BaseColumns;

/**
 * Created by dennis on 23/03/15.
 */
public final class BookmarksContract {

	public static final String BASE_CONTENT_URL = "content://com.mantz_it.rfanalyzer.provider";
	public static final Uri BASE_CONTENT_URI = Uri.parse(BASE_CONTENT_URL);

	public BookmarksContract() {}

	public static abstract class Bookmarks implements BaseColumns{
		public static final String TABLE_NAME 				= "bookmarks";
		public static final String COLUMN_NAME_NAME 		= "name";
		public static final String COLUMN_NAME_COMMENT 		= "comment";
		public static final String COLUMN_NAME_CATEGORY_ID	= "categoryId";
		public static final String COLUMN_NAME_FREQUENCY	= "frequency";
		public static final String COLUMN_NAME_CHANNEL_WIDTH= "channelWidth";
		public static final String COLUMN_NAME_MODE 		= "mode";
		public static final String COLUMN_NAME_SQUELCH 		= "squelch";
		public static final Uri CONTENT_URI 				= Uri.parse(BASE_CONTENT_URL + "/bookmarks");
	}

	public static abstract class BookmarkCategories implements BaseColumns{
		public static final String TABLE_NAME 				= "bookmarkCategories";
		public static final String COLUMN_NAME_CATEGORY_NAME= "categoryName";
		public static final String COLUMN_NAME_DESCRIPTION	= "description";
		public static final Uri CONTENT_URI 				= Uri.parse(BASE_CONTENT_URL + "/bookmarks/categories");
	}
}
