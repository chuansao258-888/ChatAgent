import { useEffect } from "react";
import { createPortal } from "react-dom";
import AuthCard from "./AuthCard.tsx";
import { useAuth } from "../../hooks/useAuth.ts";

export default function AuthDialog() {
  const { authDialogMode, authDialogOpen, closeAuthDialog } = useAuth();

  useEffect(() => {
    if (!authDialogOpen) {
      return;
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        closeAuthDialog();
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => {
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [authDialogOpen, closeAuthDialog]);

  if (!authDialogOpen) {
    return null;
  }

  return createPortal(
    <div
      className="fixed inset-0 z-[1200] flex items-center justify-center bg-black/55 px-4 py-6 backdrop-blur-[2px]"
      onPointerDown={(event) => {
        if (event.button === 0 && event.target === event.currentTarget) {
          closeAuthDialog();
        }
      }}
    >
      <div
        className="w-full max-w-[540px]"
        role="dialog"
        aria-label="Authentication"
        aria-modal="true"
      >
        <AuthCard
          initialMode={authDialogMode}
          onSuccess={closeAuthDialog}
          onClose={closeAuthDialog}
          compact
        />
      </div>
    </div>,
    document.body,
  );
}
