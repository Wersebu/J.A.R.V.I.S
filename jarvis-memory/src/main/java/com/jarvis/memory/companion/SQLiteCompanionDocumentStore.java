package com.jarvis.memory.companion;
import com.jarvis.common.companion.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.sql.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
/** Additive, isolated SQLite history file. Disabled by default; no existing memory schema is replaced. */
@Service
public class SQLiteCompanionDocumentStore implements CompanionDocumentStore,AutoCloseable,org.springframework.beans.factory.DisposableBean {
    private java.nio.channels.FileChannel lockChannel;private java.nio.channels.FileLock writerLock;private boolean closed;
    private final boolean enabled;private final String url;private final InMemoryCompanionStore memory=new InMemoryCompanionStore();
    public SQLiteCompanionDocumentStore(@Value("${jarvis.companion.history.enabled:false}") boolean enabled,@Value("${jarvis.companion.history.path:./data/companion-history.db}") String path) {
        this.enabled=enabled;this.url="jdbc:sqlite:"+Path.of(path).toAbsolutePath();
        if(enabled)try{Path target=Path.of(path).toAbsolutePath();Files.createDirectories(target.getParent());lockChannel=java.nio.channels.FileChannel.open(target.resolveSibling(target.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);writerLock=lockChannel.tryLock();if(writerLock==null)throw new IllegalStateException("Companion history is already owned by another backend process");try(var c=open();var s=c.createStatement()){
            s.execute("PRAGMA journal_mode=WAL");s.execute("CREATE TABLE IF NOT EXISTS companion_documents (owner TEXT NOT NULL,kind TEXT NOT NULL,id TEXT NOT NULL,body TEXT NOT NULL,updated_at TEXT NOT NULL,PRIMARY KEY(owner,kind,id))");
        }}catch(Exception e){close();throw new IllegalStateException("Could not initialize isolated companion history",e);}
    }
    private Connection open()throws SQLException{if(closed)throw new SQLException("Companion store is closed");Connection c=DriverManager.getConnection(url);try(var s=c.createStatement()){s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA synchronous=FULL");}return c;}
    public boolean durable(){return enabled;}
    public synchronized List<Document> all(String kind){if(!enabled)return memory.all(kind);try(var c=open();var q=c.prepareStatement("SELECT owner,kind,id,body,updated_at FROM companion_documents WHERE kind=?")){q.setString(1,kind);try(var r=q.executeQuery()){var list=new ArrayList<Document>();while(r.next())list.add(read(r));return List.copyOf(list);}}catch(SQLException e){throw failure(e);}}
    public synchronized Optional<Document> find(String owner,String kind,String id){if(!enabled)return memory.find(owner,kind,id);try(var c=open();var q=c.prepareStatement("SELECT owner,kind,id,body,updated_at FROM companion_documents WHERE owner=? AND kind=? AND id=?")){q.setString(1,owner);q.setString(2,kind);q.setString(3,id);try(var r=q.executeQuery()){return r.next()?Optional.of(read(r)):Optional.empty();}}catch(SQLException e){throw failure(e);}}
    public synchronized List<Document> documents(String owner,String kind,String prefix){if(!enabled)return memory.documents(owner,kind,prefix);try(var c=open();var q=c.prepareStatement("SELECT owner,kind,id,body,updated_at FROM companion_documents WHERE owner=? AND kind=? AND id>=? AND id<?")){q.setString(1,owner);q.setString(2,kind);q.setString(3,prefix);q.setString(4,prefix+"\uffff");try(var r=q.executeQuery()){var list=new ArrayList<Document>();while(r.next())list.add(read(r));return List.copyOf(list);}}catch(SQLException e){throw failure(e);}}
    private Document read(ResultSet r)throws SQLException{return new Document(r.getString(1),r.getString(2),r.getString(3),r.getString(4),Instant.parse(r.getString(5)));}
    public synchronized void put(Document d){if(!enabled){memory.put(d);return;}try(var c=open();var q=c.prepareStatement("INSERT INTO companion_documents(owner,kind,id,body,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(owner,kind,id) DO UPDATE SET body=excluded.body,updated_at=excluded.updated_at")){q.setString(1,d.owner());q.setString(2,d.kind());q.setString(3,d.id());q.setString(4,d.body());q.setString(5,d.updatedAt().toString());q.executeUpdate();}catch(SQLException e){throw failure(e);}}
    public synchronized void putAll(List<Document> documents){if(!enabled){memory.putAll(documents);return;}try(var c=open()){c.setAutoCommit(false);try(var q=c.prepareStatement("INSERT INTO companion_documents(owner,kind,id,body,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(owner,kind,id) DO UPDATE SET body=excluded.body,updated_at=excluded.updated_at")){for(var d:documents){q.setString(1,d.owner());q.setString(2,d.kind());q.setString(3,d.id());q.setString(4,d.body());q.setString(5,d.updatedAt().toString());q.addBatch();}q.executeBatch();c.commit();}catch(SQLException e){c.rollback();throw e;}}catch(SQLException e){throw failure(e);}}
    public synchronized void deleteTask(String owner,String id){if(!enabled){memory.deleteTask(owner,id);return;}String prefix=id+"/";try(var c=open();var q=c.prepareStatement("DELETE FROM companion_documents WHERE owner=? AND ((kind IN ('task','task-input','plan','agent-scope','agent-telemetry','agent-recovered','orchestration-root') AND id=?) OR (kind IN ('plan-revision','evidence','agent-telemetry-history') AND substr(id,1,?)=?))")){q.setString(1,owner);q.setString(2,id);q.setInt(3,prefix.length());q.setString(4,prefix);q.executeUpdate();delete(owner,"task-delivery","task-result/"+id);}catch(SQLException e){throw failure(e);}}
    public synchronized void delete(String owner,String kind,String id){remove(owner,kind,id,false);}
    public synchronized void deletePrefix(String owner,String kind,String prefix){remove(owner,kind,prefix,true);}
    private void remove(String owner,String kind,String id,boolean prefix){if(!enabled){if(prefix)memory.deletePrefix(owner,kind,id);else memory.delete(owner,kind,id);return;}try(var c=open();var q=c.prepareStatement(prefix?"DELETE FROM companion_documents WHERE owner=? AND kind=? AND substr(id,1,?)=?":"DELETE FROM companion_documents WHERE owner=? AND kind=? AND id=?")){q.setString(1,owner);q.setString(2,kind);if(prefix){q.setInt(3,id.length());q.setString(4,id);}else q.setString(3,id);q.executeUpdate();}catch(SQLException e){throw failure(e);}}
    public void destroy(){close();}
    public synchronized void close(){closed=true;try{if(writerLock!=null)writerLock.release();}catch(java.io.IOException ignored){}try{if(lockChannel!=null)lockChannel.close();}catch(java.io.IOException ignored){}}
    private IllegalStateException failure(Exception e){return new IllegalStateException("Companion history storage unavailable",e);}
}
