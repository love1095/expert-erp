interface Props {
  page: number;
  totalPages: number;
  totalElements?: number;
  onPageChange: (page: number) => void;
}

export default function Pagination({ page, totalPages, totalElements, onPageChange }: Props) {
  if (totalPages <= 1) return null;
  return (
    <div className="flex items-center gap-2 mt-4 justify-end">
      <span className="text-xs text-gray-400 mr-2">
        {totalElements != null ? `${totalElements} total · ` : ""}Page {page + 1} of {totalPages}
      </span>
      <button
        onClick={() => onPageChange(0)}
        disabled={page === 0}
        className="px-2 py-1 text-xs border border-gray-300 rounded disabled:opacity-40 hover:bg-gray-50"
      >«</button>
      <button
        onClick={() => onPageChange(page - 1)}
        disabled={page === 0}
        className="px-3 py-1 text-sm border border-gray-300 rounded disabled:opacity-40 hover:bg-gray-50"
      >Prev</button>
      <button
        onClick={() => onPageChange(page + 1)}
        disabled={page >= totalPages - 1}
        className="px-3 py-1 text-sm border border-gray-300 rounded disabled:opacity-40 hover:bg-gray-50"
      >Next</button>
      <button
        onClick={() => onPageChange(totalPages - 1)}
        disabled={page >= totalPages - 1}
        className="px-2 py-1 text-xs border border-gray-300 rounded disabled:opacity-40 hover:bg-gray-50"
      >»</button>
    </div>
  );
}
