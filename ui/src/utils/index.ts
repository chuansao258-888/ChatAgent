export const getAgentEmoji = (agentId: string): string => {
  const emojiList = ["🤖", "🧠", "🛰️", "🔧", "🧪", "⚙️", "📡", "🪄", "🛠️", "📎"];
  let hash = 0;
  for (let i = 0; i < agentId.length; i += 1) {
    hash = (hash << 5) - hash + agentId.charCodeAt(i);
    hash &= hash;
  }
  const index = Math.abs(hash) % emojiList.length;
  return emojiList[index];
};

export const formatDateTime = (dateString?: string): string => {
  if (!dateString) {
    return "";
  }

  const date = new Date(dateString);
  const now = new Date();
  const diff = now.getTime() - date.getTime();
  const days = Math.floor(diff / (1000 * 60 * 60 * 24));

  if (days === 0) {
    const hours = Math.floor(diff / (1000 * 60 * 60));
    if (hours === 0) {
      const minutes = Math.floor(diff / (1000 * 60));
      return minutes <= 0 ? "刚刚" : `${minutes} 分钟前`;
    }
    return `${hours} 小时前`;
  }

  if (days === 1) {
    return "昨天";
  }

  if (days < 7) {
    return `${days} 天前`;
  }

  return date.toLocaleDateString("zh-CN", {
    month: "short",
    day: "numeric",
  });
};
