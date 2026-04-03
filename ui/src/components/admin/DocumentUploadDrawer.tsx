import { InboxOutlined } from "@ant-design/icons";
import { Button, Drawer, Form, Typography, Upload, message } from "antd";
import { useEffect, useState } from "react";
import type { UploadFile } from "antd/es/upload/interface";
import {
  replaceKnowledgeDocument,
  uploadKnowledgeDocument,
} from "../../api/admin.ts";
import type { KnowledgeDocumentVO } from "../../types/admin.ts";

interface DocumentUploadDrawerProps {
  open: boolean;
  knowledgeBaseId: string;
  document?: KnowledgeDocumentVO | null;
  onClose: () => void;
  onSuccess: () => Promise<void> | void;
}

export default function DocumentUploadDrawer({
  open,
  knowledgeBaseId,
  document,
  onClose,
  onSuccess,
}: DocumentUploadDrawerProps) {
  const [submitting, setSubmitting] = useState(false);
  const [fileList, setFileList] = useState<UploadFile[]>([]);

  useEffect(() => {
    if (!open) {
      setFileList([]);
    }
  }, [open]);

  const handleSubmit = async () => {
    const file = fileList[0]?.originFileObj;
    if (!file) {
      message.warning("Choose one file to continue.");
      return;
    }

    setSubmitting(true);
    try {
      if (document?.id) {
        await replaceKnowledgeDocument(knowledgeBaseId, document.id, file);
        message.success(`${document.filename} queued for replacement.`);
      } else {
        await uploadKnowledgeDocument(knowledgeBaseId, file);
        message.success(`${file.name} queued for ingestion.`);
      }
      await onSuccess();
      onClose();
      setFileList([]);
    } catch (error) {
      console.error("Knowledge document upload failed:", error);
      message.error("Unable to submit the document. Please try again.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={480}
      title={document ? "Replace document" : "Upload document"}
      className="admin-dark-drawer"
      destroyOnClose
    >
      <div className="space-y-5">
        <div className="rounded-section border border-white/[0.06] bg-white/[0.04] px-5 py-4">
          <Typography.Text className="block text-sm font-medium !text-white">
            {document
              ? `Replacing ${document.filename}`
              : "Add a new knowledge document"}
          </Typography.Text>
          <Typography.Text className="mt-2 block text-sm leading-6 !text-white/60">
            The file is stored immediately and then processed asynchronously
            through the ingestion pipeline.
          </Typography.Text>
        </div>

        <Form layout="vertical">
          <Form.Item label="Document file" required>
            <Upload.Dragger
              multiple={false}
              maxCount={1}
              beforeUpload={() => false}
              fileList={fileList}
              onChange={({ fileList: nextFileList }) => {
                setFileList(nextFileList.slice(-1));
              }}
              className="!rounded-section !border-dashed !border-white/10 !bg-white/[0.04]"
            >
              <p className="ant-upload-drag-icon">
                <InboxOutlined className="!text-white/60" />
              </p>
              <p className="ant-upload-text">Click or drag a file here</p>
              <p className="ant-upload-hint">
                Markdown, PDF, Word, or text files can go through the same
                ingestion flow.
              </p>
            </Upload.Dragger>
          </Form.Item>
        </Form>

        <div className="flex items-center justify-end gap-3">
          <Button onClick={onClose} className="">
            Cancel
          </Button>
          <Button
            type="primary"
            loading={submitting}
            onClick={() => {
              void handleSubmit();
            }}
            className="admin-primary-button"
          >
            {document ? "Replace document" : "Upload document"}
          </Button>
        </div>
      </div>
    </Drawer>
  );
}
