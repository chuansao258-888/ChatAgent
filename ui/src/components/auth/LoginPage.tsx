import AuthCard from "./AuthCard.tsx";

export default function LoginPage() {
  return (
    <div className="relative flex min-h-screen items-center justify-center bg-[#212121] px-4">
      <div className="relative z-10 w-full max-w-md">
        <AuthCard />
      </div>
    </div>
  );
}
