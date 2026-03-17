import { jsx as _jsx } from "react/jsx-runtime";
import React, { useState } from "react";
import { Sender } from "@ant-design/x";
const AgentChatInput = ({ onSend }) => {
    const [message, setMessage] = useState("");
    return (_jsx(Sender, { onSubmit: () => {
            onSend(message.trim());
            setMessage("");
        }, placeholder: "\u8F93\u5165\u6D88\u606F...", value: message, onChange: setMessage }));
};
export default AgentChatInput;
