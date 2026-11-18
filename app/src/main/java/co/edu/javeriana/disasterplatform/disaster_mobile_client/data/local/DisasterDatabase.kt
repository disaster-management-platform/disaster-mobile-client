package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local

import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.assessments.*
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DisasterEntity::class, DisasterTypeEntity::class, DisasterSyncState::class, LocalAssessment::class, AssessmentCatalog::class, AssessmentFile::class, AssessmentCursor::class], version = 5, exportSchema = true)
abstract class DisasterDatabase : RoomDatabase() {
    abstract fun disasterDao(): DisasterDao
    abstract fun assessmentDao(): AssessmentDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE assessments (localId TEXT NOT NULL PRIMARY KEY, owner TEXT NOT NULL, actor TEXT NOT NULL, organization TEXT NOT NULL, disasterId INTEGER, draftJson TEXT NOT NULL, creationPayload TEXT, remoteId TEXT, revision INTEGER, version INTEGER, remoteJson TEXT, observedRemoteJson TEXT, state TEXT NOT NULL, error TEXT)")
                db.execSQL("CREATE UNIQUE INDEX index_assessments_owner_remoteId ON assessments(owner,remoteId)")
                db.execSQL("CREATE TABLE assessment_catalogs (owner TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, active INTEGER NOT NULL, damageLevel TEXT, assetTypeId TEXT, PRIMARY KEY(owner,kind,id))")
                db.execSQL("CREATE TABLE assessment_files (id TEXT NOT NULL PRIMARY KEY, assessmentId TEXT NOT NULL, owner TEXT NOT NULL, path TEXT NOT NULL, originalFileName TEXT NOT NULL, contentType TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL, intentId TEXT NOT NULL, evidenceId TEXT, revision INTEGER, reference TEXT, state TEXT NOT NULL, error TEXT, previousIntents TEXT NOT NULL, FOREIGN KEY(assessmentId) REFERENCES assessments(localId) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX index_assessment_files_assessmentId ON assessment_files(assessmentId)")
                db.execSQL("CREATE TABLE assessment_cursors (owner TEXT NOT NULL, scope TEXT NOT NULL, disaster INTEGER NOT NULL, cursor TEXT NOT NULL, PRIMARY KEY(owner,scope,disaster))")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (column in listOf("registeringOrganizationId", "emergencyResponsibleId", "geographyJson", "organizationsJson"))
                    db.execSQL("ALTER TABLE disasters ADD COLUMN $column TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS disaster_sync_state (name TEXT NOT NULL, cursor TEXT NOT NULL, PRIMARY KEY(name))")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE disasters ADD COLUMN disasterTypeId INTEGER")
                db.execSQL("ALTER TABLE disasters ADD COLUMN attentionLevel INTEGER")
                db.execSQL("ALTER TABLE disasters ADD COLUMN version INTEGER")
                for (column in listOf("startedAt", "registeredAt", "updatedAt", "phase", "creationPayload"))
                    db.execSQL("ALTER TABLE disasters ADD COLUMN $column TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS disaster_types (id INTEGER NOT NULL, name TEXT NOT NULL, isActive INTEGER NOT NULL, PRIMARY KEY(id))")
            }
        }
    }
}
