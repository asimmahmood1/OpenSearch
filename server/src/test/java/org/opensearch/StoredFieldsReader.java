/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.store.FSDirectory;

import java.nio.file.Paths;

public class StoredFieldsReader {
    public static void main(String[] args) throws Exception {
        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(Paths.get("/apollo/env/swift-eu-west-1-prod-OS_3_1AMI-ES2-p001/var/es/data/nodes/0/indices/J1hV-WG1QU2Ma6vysQZ8Nw/0/index")))) {
            StoredFields storedFields = reader.storedFields();
            for (int i = 0; i < reader.maxDoc(); i++) {
                Document document = storedFields.document(i);
                IndexableField title = document.getField("title");
                System.out.println("  " + title.name() + ": " + title.stringValue());

            }
        }
    }
}
