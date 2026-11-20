package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.fongmi.android.tv.bean.Drive;

import java.util.List;

@Dao
public interface DriveDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrUpdate(Drive drive);

    @Query("SELECT * FROM drive WHERE type = :type")
    Drive findByType(String type);

    @Query("SELECT * FROM drive")
    List<Drive> findAll();

    @Query("DELETE FROM drive WHERE type = :type")
    void delete(String type);
}
