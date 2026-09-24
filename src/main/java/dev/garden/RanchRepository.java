package dev.garden;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;

/** Existing document operations used by the analysis worker; permits isolated test storage. */
interface RanchRepository {
 ObjectNode read() throws Exception;
 ObjectNode update(long expected,Consumer<ObjectNode> mutation) throws Exception;
 ObjectNode readJob() throws Exception;
 ObjectNode book(String id)throws Exception;
 void putBook(String id,ObjectNode book)throws Exception;
 void deleteBook(String id)throws Exception;
 void writeJob(ObjectNode job) throws Exception;
}
