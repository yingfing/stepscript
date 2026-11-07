package com.yingfing.stepscript.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 脚本主表：存 StepScript 源码文本（结构化步骤随时可由 core.Parser 得到）。 */
@Entity(tableName = "scripts")
data class ScriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val source: String,          // StepScript 源码
    val enabled: Boolean = true, // 用户总开关：是否参与回放
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 脚本版本历史：AI 脚本医生每次打补丁、用户手动大改都存一份，可回滚。 */
@Entity(tableName = "script_versions")
data class ScriptVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scriptId: Long,
    val source: String,
    val comment: String,         // 例如 "AI 补丁：第5步按钮改用文本匹配"
    val createdAt: Long = System.currentTimeMillis(),
)

/** 失败记录：失败现场的截图 + 布局快照 + 出错步骤上下文。 */
@Entity(tableName = "failures")
data class FailureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scriptId: Long,
    val scriptName: String,
    val stepIndex: Int,          // 出错的步骤序号（从 0 起）
    val stepText: String,        // 出错那条语句的源码文本
    val reason: String,          // 失败原因（中文）
    val screenshotPath: String?, // 截图文件路径
    val layoutText: String,      // 压缩文本布局快照（给 AI / 布局查看器用）
    val layoutXml: String,       // 结构化 XML 布局快照（给分析器/脚本修改用）
    val createdAt: Long = System.currentTimeMillis(),
    val resolved: Boolean = false,
)

@Dao
interface ScriptDao {
    @Query("SELECT * FROM scripts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ScriptEntity>>

    @Query("SELECT * FROM scripts WHERE id = :id")
    suspend fun getById(id: Long): ScriptEntity?

    @Insert
    suspend fun insert(s: ScriptEntity): Long

    @Update
    suspend fun update(s: ScriptEntity)

    @Query("UPDATE scripts SET source = :source, updatedAt = :time WHERE id = :id")
    suspend fun updateSource(id: Long, source: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE scripts SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM scripts WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface VersionDao {
    @Query("SELECT * FROM script_versions WHERE scriptId = :scriptId ORDER BY createdAt DESC")
    fun observeFor(scriptId: Long): Flow<List<ScriptVersionEntity>>

    @Query("SELECT * FROM script_versions WHERE id = :id")
    suspend fun getById(id: Long): ScriptVersionEntity?

    @Insert
    suspend fun insert(v: ScriptVersionEntity): Long
}

@Dao
interface FailureDao {
    @Query("SELECT * FROM failures WHERE resolved = 0 ORDER BY createdAt DESC")
    fun observePending(): Flow<List<FailureEntity>>

    @Query("SELECT * FROM failures WHERE scriptId = :scriptId ORDER BY createdAt DESC")
    fun observeForScript(scriptId: Long): Flow<List<FailureEntity>>

    @Query("SELECT * FROM failures WHERE id = :id")
    suspend fun getById(id: Long): FailureEntity?

    @Insert
    suspend fun insert(f: FailureEntity): Long

    @Query("UPDATE failures SET resolved = 1 WHERE id = :id")
    suspend fun markResolved(id: Long)

    @Query("DELETE FROM failures WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [ScriptEntity::class, ScriptVersionEntity::class, FailureEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scriptDao(): ScriptDao
    abstract fun versionDao(): VersionDao
    abstract fun failureDao(): FailureDao
}
