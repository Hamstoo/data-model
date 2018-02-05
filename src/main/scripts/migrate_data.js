/**
 * https://stackoverflow.com/questions/27039083/mongodb-move-documents-from-one-collection-to-another-collection/41483981#41483981
 */

function insertBatch(collection, documents) {
    var bulkInsert = collection.initializeUnorderedBulkOp();
    var insertedIds = [];
    var id;
    documents.forEach(function(doc) {
        id = doc._id;
        // Insert without raising an error for duplicates
        bulkInsert.find({_id: id}).upsert().replaceOne(doc);
        insertedIds.push(id);
    });
    bulkInsert.execute();
    return insertedIds;
}

function deleteBatch(collection, documents) {
    var bulkRemove = collection.initializeUnorderedBulkOp();
    documents.forEach(function(doc) {
        bulkRemove.find({_id: doc._id}).removeOne();
    });
    bulkRemove.execute();
}

function moveDocuments(sourceCollection, targetCollection, filter, batchSize) {
    print("Moving " + sourceCollection.find(filter).count() + " documents from " + sourceCollection + " to " + targetCollection);
    var count;
    while ((count = sourceCollection.find(filter).count()) > 0) {
        print(count + " documents remaining");
        sourceDocs = sourceCollection.find(filter).limit(batchSize);
        idsOfCopiedDocs = insertBatch(targetCollection, sourceDocs);

        targetDocs = targetCollection.find({_id: {$in: idsOfCopiedDocs}});
        deleteBatch(sourceCollection, targetDocs);
    }
    print("Done!")
}

db.