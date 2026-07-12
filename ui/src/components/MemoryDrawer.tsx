import { useEffect, useState } from "react";
import { Alert, Button, Drawer, Empty, Input, List, Select, Space, message } from "antd";
import { createMemory, deleteMemory, getMemories, updateMemory, type MemoryItem } from "../api/api.ts";

export default function MemoryDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [items, setItems] = useState<MemoryItem[]>([]);
  const [content, setContent] = useState("");
  const [type, setType] = useState<MemoryItem["type"]>("fact");
  const [loading, setLoading] = useState(false);
  const load = async () => { setLoading(true); try { setItems(await getMemories()); } catch { message.error("Failed to load memories"); } finally { setLoading(false); } };
  useEffect(() => { if (open) void load(); }, [open]);
  const add = async () => { if (!content.trim()) return; await createMemory(type, content); setContent(""); await load(); };
  const edit = async (item: MemoryItem) => { const next = window.prompt("Correct this memory", item.content); if (next && next !== item.content) { await updateMemory(item, next); await load(); } };
  const remove = async (id: string) => { await deleteMemory(id); await load(); };
  return <Drawer title="Your memories" open={open} onClose={onClose} width={420}>
    <Alert className="mb-4" type="info" showIcon message="Long-term memories are independent of chats" description="Deleting a chat does not delete these memories. You can correct or delete them here." />
    <Space.Compact className="mb-4 w-full"><Select value={type} onChange={setType} options={[{value:"fact",label:"Fact"},{value:"preference",label:"Preference"}]} /><Input value={content} onChange={(e) => setContent(e.target.value)} placeholder="Add a memory" onPressEnter={() => void add()} /><Button type="primary" onClick={() => void add()}>Add</Button></Space.Compact>
    <List loading={loading} dataSource={items} locale={{emptyText:<Empty description="No saved memories" />}} renderItem={(item) => <List.Item actions={[<Button key="edit" type="link" onClick={() => void edit(item)}>Edit</Button>,<Button key="delete" danger type="link" onClick={() => void remove(item.id)}>Delete</Button>]}><List.Item.Meta title={item.type === "fact" ? "Fact" : "Preference"} description={item.content} /></List.Item>} />
  </Drawer>;
}
