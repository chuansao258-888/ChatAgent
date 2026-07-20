import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AuthDialog from "./AuthDialog.tsx";

const hoisted = vi.hoisted(() => ({
  closeAuthDialog: vi.fn(),
}));

vi.mock("../../hooks/useAuth.ts", () => ({
  useAuth: () => ({
    authDialogMode: "register",
    authDialogOpen: true,
    closeAuthDialog: hoisted.closeAuthDialog,
  }),
}));

vi.mock("./AuthCard.tsx", () => ({
  default: () => <input aria-label="Password" type="password" />,
}));

function getBackdrop(): HTMLElement {
  const dialog = screen.getByRole("dialog", { name: "Authentication" });
  const backdrop = dialog.parentElement;
  if (!backdrop) {
    throw new Error("Authentication dialog backdrop is missing");
  }
  return backdrop;
}

describe("AuthDialog", () => {
  beforeEach(() => {
    hoisted.closeAuthDialog.mockReset();
  });

  it("stays open when a password selection drag ends on the backdrop", () => {
    render(<AuthDialog />);

    const password = screen.getByLabelText("Password");
    const backdrop = getBackdrop();

    fireEvent.pointerDown(password, { button: 0 });
    fireEvent.pointerUp(backdrop, { button: 0 });
    fireEvent.click(backdrop, { button: 0 });

    expect(hoisted.closeAuthDialog).not.toHaveBeenCalled();
  });

  it("closes when the primary pointer starts on the backdrop", () => {
    render(<AuthDialog />);

    fireEvent.pointerDown(getBackdrop(), { button: 0 });

    expect(hoisted.closeAuthDialog).toHaveBeenCalledTimes(1);
  });
});
