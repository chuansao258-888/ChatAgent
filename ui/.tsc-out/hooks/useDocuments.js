import { useEffect, useState, useCallback } from "react";
import { getDocumentsByKbId, deleteDocument, } from "../api/api.ts";
export function useDocuments(kbId) {
    const [documents, setDocuments] = useState([]);
    const [loading, setLoading] = useState(false);
    const fetchDocuments = useCallback(async () => {
        if (!kbId) {
            setDocuments([]);
            return;
        }
        setLoading(true);
        try {
            const resp = await getDocumentsByKbId(kbId);
            setDocuments(resp.documents);
        }
        finally {
            setLoading(false);
        }
    }, [kbId]);
    useEffect(() => {
        fetchDocuments();
    }, [fetchDocuments]);
    const deleteDocumentHandle = async (documentId) => {
        await deleteDocument(documentId);
        await fetchDocuments();
    };
    return {
        documents,
        loading,
        refreshDocuments: fetchDocuments,
        deleteDocument: deleteDocumentHandle,
    };
}
